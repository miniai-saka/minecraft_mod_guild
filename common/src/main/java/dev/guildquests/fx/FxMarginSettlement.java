package dev.guildquests.fx;

import dev.guildquests.data.PlayerGuildData;
import dev.guildquests.fx.FxModels.Account;
import dev.guildquests.service.GuildDebtPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;

public final class FxMarginSettlement {
    private FxMarginSettlement() { }
    public record Result(long paid, long debtAdded) { }
    public static Result settle(FxEngine engine, Account account, PlayerGuildData wallet, long dueAt) {
        if (account.pendingMarginCall <= 0) { account.tradingLocked = wallet.delinquent; return new Result(0, 0); }
        BigDecimal claim = BigDecimal.valueOf(account.pendingMarginCall).setScale(4, RoundingMode.HALF_UP).setScale(0, RoundingMode.CEILING);
        long billed = claim.min(BigDecimal.valueOf(Long.MAX_VALUE)).longValueExact();
        long paid = Math.min(wallet.coins, billed);
        long debt = Math.min(billed - paid, Long.MAX_VALUE - wallet.debt);
        wallet.coins -= paid;
        GuildDebtPolicy.addDebt(wallet, debt, dueAt, true);
        account.pendingMarginCall = claim.subtract(BigDecimal.valueOf(paid)).subtract(BigDecimal.valueOf(debt)).max(BigDecimal.ZERO).doubleValue();
        account.tradingLocked = wallet.delinquent || account.pendingMarginCall > 0;
        if (paid > 0 || debt > 0) engine.recordMarginSettlement(account, paid, debt);
        return new Result(paid, debt);
    }
}
