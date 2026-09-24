package dev.infrai.creator;

import dev.infrai.creator.CreatorOnboardingService.Channel;
import dev.infrai.creator.CreatorOnboardingService.ProcessingState;
import dev.infrai.creator.CreatorOnboardingService.Signup;

public final class CreatorOnboardingCommand {
    private CreatorOnboardingCommand() {}

    public static void main(String[] args) {
        if (args.length != 7) {
            System.err.println("usage: <signup-id> <name> <email> <phone|-> <email|sms> <asset-title> <asset-url>");
            System.exit(2);
        }
        Signup signup = new Signup(args[0], args[1], optional(args[2]), optional(args[3]),
                Channel.valueOf(args[4].toUpperCase()), args[5], args[6], ProcessingState.READY);
        CreatorOnboardingService service = new CreatorOnboardingService(new InfraiClient(InfraiConfig.fromEnvironment()));
        CreatorOnboardingService.Delivery result = service.onboard(signup);
        System.out.println(Json.object(java.util.Map.of(
                "signup_id", result.signupId(), "user_id", result.userId(), "channel", result.channel().name().toLowerCase(),
                "message_id", result.messageId(), "asset_title", result.assetTitle(),
                "processing_state", result.processingState().name().toLowerCase())));
    }

    private static String optional(String value) { return "-".equals(value) ? null : value; }
}
