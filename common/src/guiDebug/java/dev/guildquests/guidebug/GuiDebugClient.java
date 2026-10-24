package dev.guildquests.guidebug;
import dev.guildquests.client.gui.GuildGuiActions;
import dev.guildquests.client.gui.GuildMenuScreen;
import dev.guildquests.client.gui.GuildGamblingScreen;
import dev.guildquests.gui.*;
import net.minecraft.client.*;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;

import org.lwjgl.glfw.GLFW;
import java.nio.file.*;
import java.util.*;

/** Drives real widgets and actual C2S packets in the disposable production-runtime client. */
public final class GuiDebugClient {
    private record Step(String label, Runnable run, long delay, java.util.function.BooleanSupplier ready) { }
    private final List<Step> steps = new ArrayList<>();
    private final List<String> results = new ArrayList<>();
    private int phase, oldId;
    private long next, worldTime;
    private long waitingUntil;
    private boolean ready, finished, joining;
    private Minecraft c;
    private final Set<String> gamblingStages = new HashSet<>();

    public void tick(Minecraft client) {
        boolean runScenarios = Boolean.getBoolean("guildquests.guiQa");
        if (!runScenarios && !Boolean.getBoolean("guildquests.guiQaControlOnly")) return;

        c = client;
        if (client.player == null) {
            String address = System.getProperty("guildquests.guiQaServer");
            if (!joining && address != null && client.screen instanceof TitleScreen) {
                joining = true;
                ConnectScreen.startConnecting(client.screen, client, ServerAddress.parseString(address),
                        new ServerData("Isolated GUI QA", address, false), false);
            }
            return;
        }
        try {
            Path control = Path.of("gui-control.txt");
            if (Files.exists(control)) {
                String value = Files.readString(control).trim(); Files.delete(control);
                if (value.startsWith("resume:")) { phase = Integer.parseInt(value.substring(7)); finished = false; waitingUntil = 0; next = System.currentTimeMillis() + 1000; }
                else if (value.startsWith("command:")) command(value.substring(8));
                else if (value.startsWith("click:")) click(value.substring(6));
                else if (value.startsWith("input:")) input(value.substring(6));
                else if (value.startsWith("shot:")) shot(value.substring(5));
                Files.writeString(Path.of("gui-controls.txt"), "PASS " + value + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (Exception ex) { ex.printStackTrace(); }
        if (runScenarios && !finished) {
            try { monitorGambling(); }
            catch (Exception ex) { results.add("FAIL public gambling state: " + ex); finished = true; save("FAIL"); ex.printStackTrace(); return; }
        }
        if (!runScenarios || finished || System.currentTimeMillis() < next) return;
        if (!ready) { ready = true; prepare(); next = System.currentTimeMillis() + 4000; return; }
        if (phase >= steps.size()) { finished = true; save("PASS"); command("guild"); return; }
        Step step = steps.get(phase);
        try {
            if (!step.ready().getAsBoolean()) {
                if (waitingUntil == 0) waitingUntil = System.currentTimeMillis() + 15000;
                if (System.currentTimeMillis() < waitingUntil) { next = System.currentTimeMillis() + 100; return; }
                throw new IllegalStateException("Timed out waiting for " + step.label());
            }
            waitingUntil = 0;
            c.getToasts().clear();
            step.run().run();
            results.add("PASS " + phase + " " + step.label());
            System.out.println("GUI_STEP_PASS " + phase + " " + step.label());
            Files.write(Path.of("gui-results.txt"), results);
            phase++; next = System.currentTimeMillis() + step.delay();
        } catch (Exception ex) {
            results.add("FAIL " + phase + " " + step.label() + ": " + ex);
            ex.printStackTrace(); finished = true; save("FAIL");
            try { shot("failure"); } catch (Exception ignored) { }
        }
    }
    private void step(String label, Runnable run) { steps.add(new Step(label, run, 850, () -> true)); }
    private void waitStep(String label, long ms, Runnable run) { steps.add(new Step(label, run, ms, () -> true)); }
    private void awaitStep(String label, java.util.function.BooleanSupplier ready, Runnable run) { steps.add(new Step(label, run, 850, ready)); }
    private void prepare() {
        step("seed isolated server fixtures", () -> command("guiqa setup"));
        step("guild command opens menu", () -> { if(c.screen!=null)c.screen.onClose(); command("guild"); });
        step("guild menu and responsive widgets", () -> { view(GuildMenuKind.GUILD); bounds(); shot("guild-1280x800-scale2"); worldTime = c.level.getGameTime(); });
        waitStep("world continues while GUI is open", 1500, () -> { check(!c.screen.isPauseScreen(), "menu pauses world"); });
        step("background simulation advances", () -> check(c.level.getGameTime() > worldTime + 10, "world stopped"));
        step("accept quest through button", () -> click("受注する"));
        step("active quests tab and discard confirmation", () -> { click("受注中"); click("破棄"); });
        step("discard explicit second click", () -> click("破棄を確定"));
        step("open guild bank through original navigation", () -> { click("各種メニュー"); click("ギルド銀行"); });
        step("bank direct amount input", () -> { view(GuildMenuKind.BANK); input("10"); click("金額を適用"); });
        step("bank amount synchronized from server", () -> { check(amount()==10,"bank amount not synced"); clickPrefix("入金:"); });
        step("bank deposits real coin items", () -> { check(title(1).contains("90"),"coin deposit not applied"); shot("bank"); clickPrefix("出金:"); });
        step("bank withdrawal preserves configured fee", () -> { check(title(1).equals("所持コイン: 99"),"withdrawal fee incorrect"); click("支払い方法"); click("支払い: リボ"); });
        step("payment mode toggles", () -> { check(title(19).startsWith("●"),"ribo not selected"); click("支払い: 引き落とし"); });
        step("invalid amount blocked by input", () -> { input("9999999999999999999"); check(!button("金額を適用").active,"overflow accepted"); for(var child:c.screen.children())if(child instanceof EditBox box){box.setValue("-1");check(!box.getValue().contains("-"),"negative accepted");} input("0"); });
        step("oversized amount packet sent to server", () -> GuildGuiActions.amountSender.accept(menu().containerId, Long.MAX_VALUE));
        step("server rejects oversized amount and receives negative", () -> { check(amount()==10,"server accepted oversized amount"); GuildGuiActions.amountSender.accept(menu().containerId, -1L); });
        step("server rejects negative amount", () -> check(amount()==10,"server accepted negative amount"));
        step("consumer loan entry", () -> { click("各種サービス"); click("消費者金融"); });
        step("consumer loan borrow button", () -> { view(GuildMenuKind.LOAN); click("借入 +100"); });
        step("consumer loan state reflects borrowing", () -> { check(title(1).contains("100"),"loan not added"); shot("loan"); click("返済"); });
        step("mining-only repayment explanation", () -> { check(title(13).contains("採掘"),"repayment policy missing"); click("戻る"); });
        step("stale amount packet ignored", () -> { oldId = menu().containerId; click("戻る"); GuildGuiActions.amountSender.accept(oldId, 9999L); });
        step("transfer navigation", () -> { view(GuildMenuKind.GUILD); click("各種メニュー"); click("送金"); });
        step("transfer amount and empty recipient state", () -> { view(GuildMenuKind.TRANSFER); input("1234"); click("金額を適用"); });
        step("transfer synchronizes amount", () -> { check(amount()==1234,"transfer amount mismatch"); shot("transfer"); click("戻る"); });
        step("rankings entry", () -> { click("各種メニュー"); click("ランキング"); });
        step("ranking mode control", () -> { view(GuildMenuKind.RANKING); clickPrefix("ソート切替"); });
        step("ranking updated", () -> { shot("ranking"); click("戻る"); });
        step("gambling hub entry", () -> { click("各種メニュー"); click("ギャンブル"); });
        step("gambling hub", () -> { view(GuildMenuKind.GAMBLE); shot("gambling"); click("ブラックジャック"); });
        step("blackjack bet increment", () -> { view(GuildMenuKind.BLACKJACK); click("+10"); });
        step("blackjack bet decrement", () -> { click("-10"); });
        step("blackjack restored bet and start", () -> { click("+10"); });
        step("blackjack start", () -> click("スタート"));
        step("blackjack hand controls", () -> { shot("blackjack"); if (has("ヒット")) click("ヒット"); });
        step("blackjack stand", () -> { if (has("スタンド")) click("スタンド"); });
        step("blackjack result and back", () -> { click("戻る"); });
        step("chinchirorin entry", () -> click("チンチロ"));
        step("chinchirorin bet", () -> { view(GuildMenuKind.CHINCHIRORIN); click("+10"); });
        waitStep("chinchirorin roll", 4000, () -> click("サイコロを投げる"));
        step("chinchirorin result", () -> { shot("chinchirorin"); click("戻る"); });
        step("slot entry", () -> click("スロット"));
        step("slot bet", () -> { view(GuildMenuKind.SLOT); click("+10"); });
        waitStep("slot spin", 7000, () -> click("スピン"));
        step("slot result", () -> { shot("slot"); check(has("もう一回"),"slot did not finish"); click("戻る"); });
        step("roulette entry", () -> click("ルーレット"));
        step("roulette color selection", () -> { view(GuildMenuKind.ROULETTE); clickPrefix("赤"); });
        step("roulette bet", () -> click("+10"));
        waitStep("roulette spin", 7000, () -> click("ルーレットを回す"));
        step("roulette result", () -> { shot("roulette"); check(has("もう一回"),"roulette did not finish"); click("戻る"); });
        step("coin flip entry", () -> click("コイントス"));
        step("coin flip side", () -> { view(GuildMenuKind.COIN_FLIP); click("表 (オモテ)"); });
        step("coin flip bet", () -> click("+10"));
        waitStep("coin flip animation", 5000, () -> click("コインを投げる"));
        step("coin flip result", () -> { shot("coin-flip"); check(has("もう一回"),"coin flip did not finish"); click("戻る"); });
        step("horse race entry", () -> click("競馬"));
        step("horse selection", () -> { view(GuildMenuKind.HORSE_RACE); clickPrefix("白馬"); click("馬・賭け金"); });
        step("horse race bet", () -> click("+10"));
        step("horse race start", () -> { click("レース"); click("レース開始"); });
        waitStep("horse race live animation", 12000, () -> { shot("horse-race-running"); });
        step("horse race result", () -> { shot("horse-race-result"); check(has("もう一回"),"race did not finish"); click("戻る"); });
        step("FX entry remains available", () -> { clickPrefix("Guild FX"); });
        step("FX opens existing dedicated screen", () -> { check(c.screen instanceof dev.guildquests.client.FxScreen,"FX navigation failed"); c.screen.onClose(); command("guild auction"); });
        step("auction marketplace", () -> { view(GuildMenuKind.AUCTION_BROWSE); shot("auction"); input("25"); click("金額を適用"); });
        step("auction bid amount", () -> { check(amount()==25,"bid input mismatch"); click(title(9)); });
        step("auction buyout delivers mailbox", () -> { click(title(9)); });
        step("auction service navigation", () -> { click("出品・受取・案内"); click("出品する"); });
        step("auction sell direct price", () -> { view(GuildMenuKind.AUCTION_SELL); input("150"); click("金額を適用"); });
        step("auction sell mode control", () -> { click("形式: 固定価格"); });
        step("auction sell creates listing", () -> { shot("auction-sell"); click("「オークション」で出品"); });
        step("back to marketplace", () -> click("戻る"));
        step("open listings and mailbox", () -> { click("出品・受取・案内"); click("自分の出品・受取ボックス"); });
        step("own listings cancel button", () -> { view(GuildMenuKind.AUCTION_MINE); click(title(9)); });
        step("mailbox claim button", () -> { click("受取ボックス"); shot("mailbox"); click(title(27)); });
        step("mailbox claim all", () -> { click("案内・一括受取"); click("受取ボックスを全て受け取る"); });
        step("admin command opens GUI", () -> command("guildadmin"));
        step("admin player selection", () -> { view(GuildMenuKind.ADMIN); shot("admin-players"); click("GuiTester"); });
        step("admin add loan", () -> click("借入を追加"));
        step("admin numeric input", () -> { input("1234"); click("金額を適用"); });
        step("admin review", () -> { check(amount()==1234,"admin input mismatch"); click("内容を確認"); });
        step("admin apply after review", () -> { shot("admin-confirm"); click("確定する"); });
        step("admin loan result", () -> { check(lore(22).contains("借入: 1334 GC"),"admin loan incorrect"); click("借入を指定額に設定"); });
        step("admin revoked permissions", () -> command("guiqa security"));
        step("permission revocation closes menu", () -> { check(!(c.screen instanceof GuildMenuScreen),"revoked admin menu remains open"); command("guiqa bankfree"); });
        step("bank block fee waiver preserved", () -> { view(GuildMenuKind.BANK); check(title(2).contains("無料"),"fee waiver lost"); command("guiqa deposit"); });
        step("deposit block settings", () -> { view(GuildMenuKind.DEPOSIT_SETTINGS); shot("deposit-settings"); click("● 入金通知: ON"); });
        step("deposit notification changed", () -> { check(title(1).contains("OFF"),"notification not toggled"); click("レッドストーン出力: OFF"); });
        step("deposit redstone changed", () -> { check(title(6).contains("ON"),"redstone not toggled"); click("預け入れ: 1"); });
        step("ordinary chest remains vanilla", () -> command("guiqa normalchest"));
        step("same title does not hijack normal chest", () -> { check(c.screen instanceof ContainerScreen,"normal chest replaced"); c.screen.onClose(); command("guild"); });
        step("narrow window resize", () -> resize(854,480,2));
        step("narrow window layout and pagination", () -> { bounds(); shot("guild-854x480-scale2"); click("各種メニュー"); click("ギルド銀行"); });
        step("narrow bank amount editing", () -> { bounds(); input("42"); click("金額を適用"); });
        step("narrow bank synchronized", () -> { check(amount()==42,"narrow input failed"); shot("bank-854x480-scale2"); resize(1280,800,4); });
        step("large GUI scale minimum viewport", () -> { bounds(); shot("bank-1280x800-scale4"); resize(1920,1080,2); });
        step("wide screen layout", () -> { bounds(); shot("bank-1920x1080-scale2"); resize(1280,800,2); });
        step("Escape closes while typing", () -> { input("99"); c.screen.keyPressed(GLFW.GLFW_KEY_ESCAPE,0,0); });
        step("Escape closed server menu", () -> { check(c.screen==null,"Escape did not close"); command("guiqa persist"); command("save-all flush"); });
        prepareGamblingViews();
    }

    private net.minecraft.nbt.CompoundTag gameView() {
        return menu().getSlot(0).getItem().getOrCreateTag().getCompound(GuildGamblingView.TAG);
    }

    private void monitorGambling() throws java.io.IOException {
        if (!(c.screen instanceof GuildGamblingScreen)) return;
        var v = gameView(); String phase = v.getString("phase"); if (phase.isEmpty()) return;
        String key = menu().kind + ":" + phase;
        boolean animating = phase.equals("SPINNING") || phase.equals("FLIPPING") || phase.equals("RACING");
        if (animating) check(!v.contains("result") && !v.contains("payout"), "outcome leaked during animation");
        if (menu().kind == GuildMenuKind.ROULETTE && phase.equals("SPINNING")) check(v.getInt("number") == -1, "roulette number leaked");
        if (menu().kind == GuildMenuKind.COIN_FLIP && phase.equals("FLIPPING")) check(v.getInt("side") == -1, "coin side leaked");
        if (menu().kind == GuildMenuKind.HORSE_RACE && phase.equals("RACING"))
            check(v.getInt("winner") == -1 && !v.contains("plannedWinner"), "race winner leaked");
        if (menu().kind == GuildMenuKind.BLACKJACK && phase.equals("PLAYER_TURN")) {
            var dealer = v.getList("dealerCards", net.minecraft.nbt.Tag.TAG_COMPOUND);
            if (dealer.size() > 1) check(dealer.getCompound(1).getAllKeys().equals(Set.of("hidden")), "dealer hole card leaked");
        }
        if (gamblingStages.add(key)) {
            Files.writeString(Path.of("gambling-stages.txt"), key + " viewport=" + c.screen.width + "x" + c.screen.height + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (animating) shot("visual-" + menu().kind.id() + "-running");
        }
        if (menu().kind == GuildMenuKind.SLOT && phase.equals("SPINNING")) {
            int mask = 0; int[] reels = v.getIntArray("reels");
            for (int i = 0; i < reels.length; i++) if (reels[i] >= 0) mask |= 1 << i;
            String stop = "SLOT:stops-" + mask;
            if (gamblingStages.add(stop)) { Files.writeString(Path.of("gambling-stages.txt"), stop + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); shot("visual-slot-stops-" + mask); }
        }
    }

    private void prepareGamblingViews() {
        step("visual game checks return to guild", () -> command("guild"));
        awaitStep("visual game checks open game hub", () -> c.screen instanceof GuildMenuScreen screen && screen.getMenu().kind == GuildMenuKind.GUILD,
                () -> { click("各種メニュー"); click("ギャンブル"); });
        GuildMenuKind[] kinds = {GuildMenuKind.BLACKJACK, GuildMenuKind.CHINCHIRORIN, GuildMenuKind.SLOT,
                GuildMenuKind.ROULETTE, GuildMenuKind.COIN_FLIP, GuildMenuKind.HORSE_RACE};
        String[] names = {"ブラックジャック", "チンチロ", "スロット", "ルーレット", "コイントス", "競馬"};
        for (int i = 0; i < kinds.length; i++) {
            GuildMenuKind kind = kinds[i]; String name = names[i];
            step("dedicated " + kind + " screen", () -> click(name));
            step(kind + " full viewport", () -> { view(kind); check(c.screen instanceof GuildGamblingScreen, "generic game screen");
                check(!c.screen.isPauseScreen(), "game paused world"); bounds(); shot("visual-" + kind.id() + "-wide"); if(has("もう一回"))click("もう一回"); });
            step(kind + " resize", () -> resize(854,480,4));
            step(kind + " compact board", () -> { bounds(); shot("visual-" + kind.id() + "-compact"); click(kind==GuildMenuKind.HORSE_RACE?"馬・賭け金":"賭け金"); });
            step(kind + " compact bet controls", () -> { bounds(); click("+10"); });
            step(kind + " compact bet synchronized", () -> { check(gameView().getLong("bet")==10,"compact bet not synced"); shot("visual-"+kind.id()+"-bet"); click(kind==GuildMenuKind.HORSE_RACE?"レース":"戻る"); });
            step(kind + " restore viewport", () -> resize(1280,800,2));
            if (kind == GuildMenuKind.SLOT) {
                waitStep("slot machine lever starts real spin", 2600, () -> {
                    var l = dev.guildquests.client.gui.GamblingLayout.of(c.screen.width,c.screen.height);
                    int machineWidth=l.width()>=560?l.width()-180:l.width()-16;
                    check(c.screen.mouseClicked(l.left()+8+machineWidth-18,l.boardY()+18,0),"lever click not consumed");
                });
                step("lever spin settles and displays result", () -> { check(gameView().getString("phase").equals("DONE"),"lever spin did not finish"); shot("visual-slot-lever-result"); });
            }
            if (kind == GuildMenuKind.BLACKJACK) {
                step("eleven-card blackjack fixture", () -> command("guiqa blackjackhand"));
                step("all eleven cards and hidden dealer", () -> { check(gameView().getList("playerCards",net.minecraft.nbt.Tag.TAG_COMPOUND).size()==11,"long hand truncated"); bounds(); shot("visual-blackjack-eleven-cards"); resize(854,480,4); });
                step("long blackjack hand compact", () -> { bounds(); shot("visual-blackjack-eleven-cards-compact"); resize(1280,800,2); });
            }
            step(kind + " navigation back", () -> { bounds(); click("戻る"); });
        }
        step("all animated server phases observed", () -> {
            for(String stage:new String[]{"SLOT:SPINNING","ROULETTE:SPINNING","COIN_FLIP:FLIPPING","HORSE_RACE:RACING","BLACKJACK:PLAYER_TURN"})
                check(gamblingStages.contains(stage),"missing phase "+stage);
            command("guiqa persist"); command("save-all flush");
        });
    }

    private void command(String text) { c.player.connection.sendCommand(text); }
    private GuildClientMenu menu() { check(c.player.containerMenu instanceof GuildClientMenu,"no guild menu"); return (GuildClientMenu)c.player.containerMenu; }
    private void view(GuildMenuKind kind) { check(c.screen instanceof GuildMenuScreen && menu().kind==kind,"expected "+kind+" got "+(c.screen==null?"null":c.screen.getTitle().getString())); }
    private String title(int slot) { return menu().getSlot(slot).getItem().getHoverName().getString(); }
    private String lore(int slot) { return String.valueOf(menu().getSlot(slot).getItem().getTag()); }
    private long amount() { for(int i=0;i<menu().kind.rows*9;i++){var t=menu().getSlot(i).getItem().getTag();if(t!=null&&t.contains("GuildGuiAmount"))return t.getCompound("GuildGuiAmount").getLong("value");}throw new IllegalStateException("no amount"); }
    private Button button(String name) { for(var child:c.screen.children()) if(child instanceof Button b && b.getMessage().getString().equals(name)) return b; throw new IllegalStateException("missing button "+name+"; visible="+labels()); }
    private String labels() { return c.screen.children().stream().filter(e->e instanceof Button).map(e->((Button)e).getMessage().getString()).toList().toString(); }
    private void click(String name) { seek(name,false,true); }
    private void clickPrefix(String name) { seek(name,true,true); }
    private boolean has(String name) { return seek(name,false,false); }
    private boolean seek(String name,boolean prefix,boolean press) {
        if(c.screen instanceof GuildGamblingScreen) {
            for(var child:List.copyOf(c.screen.children())) if(child instanceof Button b && b.active && (prefix?b.getMessage().getString().startsWith(name):b.getMessage().getString().equals(name))) {
                if(press)c.screen.mouseClicked(b.getX()+b.getWidth()/2.0,b.getY()+b.getHeight()/2.0,0); return true;
            }
            if(press)throw new IllegalStateException("missing active game button "+name+"; visible="+labels());
            return false;
        }
        for(int page=0;page<60;page++) {
            for(var child:List.copyOf(c.screen.children())) if(child instanceof Button b && b.active && (prefix?b.getMessage().getString().startsWith(name):b.getMessage().getString().equals(name))) {
                if(press)c.screen.mouseClicked(b.getX()+b.getWidth()/2.0,b.getY()+10,0);return true;
            }
            Button nextButton=button(">");
            if(!nextButton.active)break;
            c.screen.mouseClicked(nextButton.getX()+12,nextButton.getY()+10,0);
        }
        // Return to the beginning once, for helpers called after a previous search changed pages.
        for(int page=0;page<60;page++) { Button prev=button("<");if(!prev.active)break;c.screen.mouseClicked(prev.getX()+12,prev.getY()+10,0); }
        for(int page=0;page<60;page++) {
            for(var child:List.copyOf(c.screen.children())) if(child instanceof Button b && b.active && (prefix?b.getMessage().getString().startsWith(name):b.getMessage().getString().equals(name))) {
                if(press)c.screen.mouseClicked(b.getX()+b.getWidth()/2.0,b.getY()+10,0);return true;
            }
            Button nextButton=button(">");if(!nextButton.active)break;c.screen.mouseClicked(nextButton.getX()+12,nextButton.getY()+10,0);
        }
        if(press)throw new IllegalStateException("missing active button "+name+"; visible="+labels());
        return false;
    }
    private void input(String value) {
        EditBox input=null;
        for(var child:c.screen.children())if(child instanceof EditBox box){input=box;break;}
        check(input!=null,"no amount input");
        c.screen.mouseClicked(input.getX()+6,input.getY()+8,0);
        input.setValue("");
        for(char ch:value.toCharArray())c.screen.charTyped(ch,0);
    }
    private void bounds() { for(var child:c.screen.children())if(child instanceof AbstractWidget w) check(w.getX()>=0&&w.getY()>=0&&w.getX()+w.getWidth()<=c.screen.width&&w.getY()+w.getHeight()<=c.screen.height,"widget outside viewport "+w.getMessage().getString()); }
    private void resize(int w,int h,int scale) { c.options.guiScale().set(scale); GLFW.glfwSetWindowSize(c.getWindow().getWindow(),w,h); c.resizeDisplay(); }
    private void shot(String name) {
        try { Files.createDirectories(Path.of("screenshots"));try(var image=Screenshot.takeScreenshot(c.getMainRenderTarget())){image.writeToFile(Path.of("screenshots",name+".png"));} }
        catch(Exception e){throw new RuntimeException(e);}
    }
    private void check(boolean ok,String message) { if(!ok)throw new IllegalStateException(message); }
    private void save(String status) {
        try { Files.write(Path.of("gui-results.txt"),results); Files.writeString(Path.of("gui-status.txt"),status+" phase="+phase+"/"+steps.size()); }
        catch(Exception ex){ex.printStackTrace();}
        System.out.println("GUI_QA_"+status+" "+phase+"/"+steps.size());
    }
}
