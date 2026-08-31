package dev.bob.openmarket.auth.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;
import java.time.Instant;

/**
 * Outbound email. Two modes, chosen by configuration:
 * - `spring.mail.host` set (e.g. Mailpit in compose) → real SMTP
 * - otherwise → dev mode: the full mail is printed to the app log, which is
 *   exactly what the flow test greps to complete email flows end-to-end.
 */
@Component
public class EmailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EmailDispatcher.class);

    private final JavaMailSender mailSender;
    private final String host;
    private final String from;

    public EmailDispatcher(org.springframework.beans.factory.ObjectProvider<JavaMailSender> mailSender,
                           @Value("${spring.mail.host:}") String host,
                           @Value("${spring.mail.from:OpenMarket <no-reply@openmarket.dev>}") String from) {
        this.mailSender = mailSender.getIfAvailable();
        this.host = host;
        this.from = from;
    }

    public boolean isSmtpConfigured() {
        return mailSender != null && host != null && !host.isBlank();
    }

    public void send(String to, String subject, String body) {
        if (!isSmtpConfigured()) {
            log.info("[email:dev] from={} to={} subject={}\n{}", from, to, subject, body);
            return;
        }
        try {
            var message = mailSender.createMimeMessage();
            var helper = new org.springframework.mail.javamail.MimeMessageHelper(message, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, false);
            mailSender.send(message);
            log.info("[email:smtp] sent to={} subject={}", to, subject);
        } catch (Exception e) {
            log.error("[email:smtp] send failed to={}: {}", to, e.getMessage());
            throw new IllegalStateException("Email delivery failed", e);
        }
    }
}
