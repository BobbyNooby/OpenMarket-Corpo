package dev.bob.openmarket.auth.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Outbound email. Two modes, chosen by configuration:
 * - `spring.mail.host` set (e.g. Mailpit in compose) → real SMTP
 * - otherwise → dev mode: recipient + subject are always logged at INFO; the
 *   full body (which carries live reset links) only with `auth.mail.log-full`
 *   = true — the flow test sets it. Default off so a prod misconfiguration
 *   can't leak password-reset tokens into log aggregation.
 */
@Component
public class EmailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EmailDispatcher.class);

    private final JavaMailSender mailSender;
    private final String host;
    private final String from;
    private final boolean logFullBody;

    public EmailDispatcher(org.springframework.beans.factory.ObjectProvider<JavaMailSender> mailSender,
                           @Value("${spring.mail.host:}") String host,
                           @Value("${spring.mail.from:OpenMarket <no-reply@openmarket.dev>}") String from,
                           @Value("${auth.mail.log-full:false}") boolean logFullBody) {
        this.mailSender = mailSender.getIfAvailable();
        this.host = host;
        this.from = from;
        this.logFullBody = logFullBody;
    }

    public boolean isSmtpConfigured() {
        return mailSender != null && host != null && !host.isBlank();
    }

    public void send(String to, String subject, String body) {
        if (!isSmtpConfigured()) {
            // Recipient + subject are safe metadata; the body embeds one-time
            // links, so it only hits the logs when explicitly opted in.
            log.info("[email:dev] from={} to={} subject={}", from, to, subject);
            if (logFullBody) {
                log.info("[email:dev] body:\n{}", body);
            }
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
