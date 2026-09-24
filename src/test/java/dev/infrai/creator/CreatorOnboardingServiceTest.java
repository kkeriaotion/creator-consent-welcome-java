package dev.infrai.creator;

import java.util.ArrayList;
import java.util.List;
import dev.infrai.creator.CreatorOnboardingService.Channel;
import dev.infrai.creator.CreatorOnboardingService.ProcessingState;
import dev.infrai.creator.CreatorOnboardingService.Signup;

public final class CreatorOnboardingServiceTest {
    public static void main(String[] args) {
        FakeGateway gateway = new FakeGateway();
        CreatorOnboardingService service = new CreatorOnboardingService(gateway);
        Signup signup = new Signup("signup-1042", "Mina", "mina@example.com", "+15550104",
                Channel.EMAIL, "Launch checklist", "https://downloads.example/launch-1042", ProcessingState.READY);

        CreatorOnboardingService.Delivery delivery = service.onboard(signup);

        check(delivery.channel() == Channel.SMS, "suppressed email must fall back to SMS");
        check("sms-9001".equals(delivery.messageId()), "SMS message id must be returned");
        check(gateway.calls.equals(List.of("account:mina@example.com:creator-account:signup-1042", "email-check:mina@example.com",
                "sms-check:+15550104", "sms-send:+15550104:creator-welcome:signup-1042:sms")), "unexpected handoff: " + gateway.calls);
        System.out.println("PASS email suppression selects consented SMS delivery");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class FakeGateway implements CreatorOnboardingService.Gateway {
        final List<String> calls = new ArrayList<>();
        public String createUser(String email, String name, String signupChannel, String processingState, String requestId) {
            calls.add("account:" + email + ":" + requestId);
            return "user-88";
        }
        public boolean emailSuppressed(String email) { calls.add("email-check:" + email); return true; }
        public String sendEmail(String email, String subject, String html, String requestId) { throw new AssertionError("email sent"); }
        public boolean smsSuppressed(String phone) { calls.add("sms-check:" + phone); return false; }
        public String sendSms(String phone, String message, String requestId) {
            calls.add("sms-send:" + phone + ":" + requestId);
            check(message.contains("Launch checklist"), "asset handoff missing");
            return "sms-9001";
        }
    }
}
