package dev.guildquests.net;

/** Client-built payload for `publish|submit|<json>`. All numbers are strings for robust parsing. */
public final class PublishRequest {
    public String type = "KILL";
    public String target = "";
    public String amount = "1";
    public String reward = "1";
    public String title = "";
    public String description = "";

    public PublishRequest() {
    }
}
