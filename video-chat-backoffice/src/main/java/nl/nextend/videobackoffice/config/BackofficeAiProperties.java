package nl.nextend.videobackoffice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("backoffice.ai")
public class BackofficeAiProperties {

    private boolean enabled;
    private String apiKey = "";
    private String model = "gpt-5-mini";
    private String assistantName = "Pulse Copilot";
    private int maxContextMessages = 8;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model == null || model.isBlank() ? "gpt-5-mini" : model.trim();
    }

    public String getAssistantName() {
        return assistantName;
    }

    public void setAssistantName(String assistantName) {
        this.assistantName = assistantName == null || assistantName.isBlank()
            ? "Pulse Copilot"
            : assistantName.trim();
    }

    public int getMaxContextMessages() {
        return maxContextMessages;
    }

    public void setMaxContextMessages(int maxContextMessages) {
        this.maxContextMessages = Math.max(1, Math.min(maxContextMessages, 20));
    }
}
