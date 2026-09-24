package dev.infrai.creator;

import java.util.Objects;

public final class CreatorOnboardingService {
    public enum Channel { EMAIL, SMS }
    public enum ProcessingState { READY, PENDING_REVIEW }
    public record Signup(String signupId, String creatorName, String email, String phone,
                         Channel signupChannel, String assetTitle, String assetDownloadUrl, ProcessingState processingState) {}
    public record Delivery(String signupId, String userId, Channel channel, String messageId, String assetTitle, ProcessingState processingState) {}

    interface Gateway {
        String createUser(String email, String name, String signupChannel, String processingState, String requestId);
        boolean emailSuppressed(String email);
        String sendEmail(String email, String subject, String html, String requestId);
        boolean smsSuppressed(String phone);
        String sendSms(String phone, String message, String requestId);
    }

    private final Gateway infrai;

    public CreatorOnboardingService(Gateway infrai) { this.infrai = Objects.requireNonNull(infrai); }

    public Delivery onboard(Signup signup) {
        validate(signup);
        if (signup.email() == null) throw new IllegalArgumentException("Account creation requires an email");
        String userId = infrai.createUser(signup.email(), signup.creatorName(), signup.signupChannel().name().toLowerCase(),
                signup.processingState().name().toLowerCase(), "creator-account:" + signup.signupId());
        Channel selected = selectChannel(signup,
                signup.email() != null && !infrai.emailSuppressed(signup.email()));
        String requestId = "creator-welcome:" + signup.signupId();
        String messageId;
        if (selected == Channel.EMAIL) {
            String html = "<p>Welcome, " + escape(signup.creatorName()) + ".</p><p>Your "
                    + escape(signup.assetTitle()) + " is ready: <a href=\"" + escape(signup.assetDownloadUrl()) + "\">download</a>.</p>";
            messageId = infrai.sendEmail(signup.email(), "Your creator asset is ready", html, requestId + ":email");
        } else {
            if (signup.phone() == null || infrai.smsSuppressed(signup.phone())) {
                throw new IllegalStateException("No consented delivery channel is available");
            }
            String message = "Welcome " + signup.creatorName() + ". " + signup.assetTitle() + ": " + signup.assetDownloadUrl();
            messageId = infrai.sendSms(signup.phone(), message, requestId + ":sms");
        }
        return new Delivery(signup.signupId(), userId, selected, messageId, signup.assetTitle(), signup.processingState());
    }

    static Channel selectChannel(Signup signup, boolean emailDeliverable) {
        if (signup.signupChannel() == Channel.EMAIL && emailDeliverable) return Channel.EMAIL;
        if (signup.signupChannel() == Channel.SMS && signup.phone() != null) return Channel.SMS;
        if (emailDeliverable) return Channel.EMAIL;
        return Channel.SMS;
    }

    private static void validate(Signup signup) {
        Objects.requireNonNull(signup);
        Objects.requireNonNull(signup.signupId());
        Objects.requireNonNull(signup.creatorName());
        Objects.requireNonNull(signup.signupChannel());
        Objects.requireNonNull(signup.assetTitle());
        Objects.requireNonNull(signup.assetDownloadUrl());
        Objects.requireNonNull(signup.processingState());
        if (signup.email() == null && signup.phone() == null) throw new IllegalArgumentException("An email or phone is required");
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
