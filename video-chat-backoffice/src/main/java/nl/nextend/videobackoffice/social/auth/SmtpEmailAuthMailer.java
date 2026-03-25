package nl.nextend.videobackoffice.social.auth;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import nl.nextend.videobackoffice.config.BackofficeSocialProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "spring.mail", name = "host")
public class SmtpEmailAuthMailer implements EmailAuthMailer {

    private final JavaMailSender mailSender;
    private final BackofficeSocialProperties properties;

    public SmtpEmailAuthMailer(JavaMailSender mailSender, BackofficeSocialProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @Override
    public void sendHtml(String toEmail, String toName, String subject, String htmlBody) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setTo(new InternetAddress(toEmail, toName).toUnicodeString());
            helper.setFrom(new InternetAddress(
                properties.getEmail().getFromAddress(),
                properties.getEmail().getFromName()
            ).toUnicodeString());
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            mailSender.send(message);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to send email authentication message.", exception);
        }
    }
}
