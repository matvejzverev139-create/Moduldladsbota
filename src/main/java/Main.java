import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;

public class Main {
    public static void main(String[] args) throws Exception {
        // Токен берётся из переменной окружения BOT_TOKEN
        String token = System.getenv("BOT_TOKEN");

        if (token == null) {
            System.err.println("Задай переменную BOT_TOKEN");
            System.exit(1);
        }

        JDABuilder.createDefault(token)
                .enableIntents(GatewayIntent.GUILD_VOICE_STATES)
                .enableCache(CacheFlag.VOICE_STATE)
                .setMemberCachePolicy(MemberCachePolicy.VOICE)
                .addEventListeners(new VoiceModule())
                .build();
    }
}
