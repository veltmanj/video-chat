package nl.nextend.videobackoffice.social.auth;

public interface EmailAuthMailer {

    void sendHtml(String toEmail, String toName, String subject, String htmlBody);
}
