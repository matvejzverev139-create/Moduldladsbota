import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.mediagallery.MediaGallery;
import net.dv8tion.jda.api.components.mediagallery.MediaGalleryItem;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.PermissionOverride;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.GuildReadyEvent;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.utils.FileUpload;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Модуль приватных голосовых каналов.
 *
 * Подключение:
 *   jda.addEventListener(new VoiceModule());
 *
 * JDA должен быть создан с:
 *   - GatewayIntent.GUILD_VOICE_STATES
 *   - CacheFlag.VOICE_STATE
 *   - MemberCachePolicy.VOICE (или ALL)
 *
 * Настройка на сервере: команда /setupvoice (только администраторы).
 * Настройки и баннер хранятся в папке data/ рядом с ботом.
 */
public class VoiceModule extends ListenerAdapter {

    private static final String PREFIX = "vc:";        // кнопки панели
    private static final String SETUP = "vcs:";        // элементы настроек
    private static final Path DATA_DIR = Path.of("data");
    private static final Path CONFIG_FILE = DATA_DIR.resolve("voice-config.properties");
    private static final long MAX_BANNER_SIZE = 8L * 1024 * 1024;

    /** Настройки одного сервера. */
    private static class Config {
        long panelChannelId;      // текстовый канал с панелью
        long creatorChannelId;    // войс-создатель
        long roleId;              // роль, которой доступно меню (0 = всем)
        long publishedChannelId;  // где сейчас опубликована панель
        long publishedMessageId;
        String bannerFile;        // имя файла баннера в data/ (или null)
    }

    private final Map<Long, Config> configs = new ConcurrentHashMap<>();

    /** channelId -> ownerId. Для продакшена храни в БД (иначе после рестарта теряется). */
    private final Map<Long, Long> owners = new ConcurrentHashMap<>();

    public VoiceModule() {
        load();
    }

    private Config cfg(Guild guild) {
        return configs.computeIfAbsent(guild.getIdLong(), id -> new Config());
    }

    // ───────────────────────── Сохранение настроек ─────────────────────────

    private synchronized void save() {
        Properties p = new Properties();
        configs.forEach((gid, c) -> {
            p.setProperty(gid + ".panelChannelId", String.valueOf(c.panelChannelId));
            p.setProperty(gid + ".creatorChannelId", String.valueOf(c.creatorChannelId));
            p.setProperty(gid + ".roleId", String.valueOf(c.roleId));
            p.setProperty(gid + ".publishedChannelId", String.valueOf(c.publishedChannelId));
            p.setProperty(gid + ".publishedMessageId", String.valueOf(c.publishedMessageId));
            if (c.bannerFile != null) p.setProperty(gid + ".bannerFile", c.bannerFile);
        });
        try {
            Files.createDirectories(DATA_DIR);
            try (var out = Files.newOutputStream(CONFIG_FILE)) {
                p.store(out, "voice module config");
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    private void load() {
        if (!Files.exists(CONFIG_FILE)) return;
        Properties p = new Properties();
        try (var in = Files.newInputStream(CONFIG_FILE)) {
            p.load(in);
        } catch (IOException ex) {
            ex.printStackTrace();
            return;
        }
        Set<Long> ids = new HashSet<>();
        for (String key : p.stringPropertyNames()) {
            try {
                ids.add(Long.parseLong(key.substring(0, key.indexOf('.'))));
            } catch (Exception ignored) {
            }
        }
        for (long gid : ids) {
            Config c = new Config();
            c.panelChannelId = Long.parseLong(p.getProperty(gid + ".panelChannelId", "0"));
            c.creatorChannelId = Long.parseLong(p.getProperty(gid + ".creatorChannelId", "0"));
            c.roleId = Long.parseLong(p.getProperty(gid + ".roleId", "0"));
            c.publishedChannelId = Long.parseLong(p.getProperty(gid + ".publishedChannelId", "0"));
            c.publishedMessageId = Long.parseLong(p.getProperty(gid + ".publishedMessageId", "0"));
            c.bannerFile = p.getProperty(gid + ".bannerFile");
            configs.put(gid, c);
        }
    }

    // ───────────────────────── Регистрация команды ─────────────────────────

    /** Регистрируем команду на сервере (появляется мгновенно, в отличие от глобальной). */
    private void registerCommands(Guild guild) {
        guild.upsertCommand(
                Commands.slash("setupvoice", "Настройка приватных голосовых комнат")
                        .addOption(OptionType.ATTACHMENT, "banner", "Картинка-баннер для панели", false)
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR))
        ).queue(null, err -> System.err.println("Не удалось зарегистрировать команду на " + guild.getName() + ": " + err));
    }

    @Override
    public void onGuildReady(GuildReadyEvent event) {
        registerCommands(event.getGuild());
    }

    @Override
    public void onGuildJoin(GuildJoinEvent event) {
        registerCommands(event.getGuild());
    }

    @Override
    public void onReady(ReadyEvent event) {
        // чистим старые глобальные версии, чтобы команды не дублировались
        event.getJDA().retrieveCommands().queue(list -> list.stream()
                .filter(c -> c.getName().equals("voicepanel") || c.getName().equals("setupvoice"))
                .forEach(c -> c.delete().queue()));
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent e) {
        if (!e.getName().equals("setupvoice")) return;

        // сразу подтверждаем команду, чтобы не словить "Unknown interaction" (лимит 3 сек)
        e.deferReply(true).queue();

        Guild guild = e.getGuild();
        Member m = e.getMember();
        if (guild == null || m == null || !m.hasPermission(Permission.ADMINISTRATOR)) {
            e.getHook().editOriginal("❌ Эта команда только для администраторов.").queue();
            return;
        }

        var banner = e.getOption("banner", o -> o.getAsAttachment());
        if (banner == null) {
            e.getHook().editOriginalComponents(buildSettings(guild)).useComponentsV2().queue();
            return;
        }

        if (!banner.isImage()) {
            e.getHook().editOriginal("❌ Нужна картинка (png, jpg, gif, webp).").queue();
            return;
        }
        if (banner.getSize() > MAX_BANNER_SIZE) {
            e.getHook().editOriginal("❌ Файл слишком большой (максимум 8 МБ).").queue();
            return;
        }

        Config c = cfg(guild);
        String ext = banner.getFileExtension() == null ? "png" : banner.getFileExtension().toLowerCase();
        String fileName = "banner-" + guild.getId() + "." + ext;

        banner.getProxy().download().thenAccept(is -> {
            try (is) {
                Files.createDirectories(DATA_DIR);
                Files.copy(is, DATA_DIR.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
                if (c.bannerFile != null && !c.bannerFile.equals(fileName)) {
                    Files.deleteIfExists(DATA_DIR.resolve(c.bannerFile));
                }
                c.bannerFile = fileName;
                save();
                e.getHook().editOriginalComponents(buildSettings(guild)).useComponentsV2().queue();
            } catch (IOException ex) {
                ex.printStackTrace();
                e.getHook().editOriginal("❌ Не удалось сохранить баннер.").queue();
            }
        }).exceptionally(ex -> {
            e.getHook().editOriginal("❌ Не удалось скачать картинку.").queue();
            return null;
        });
    }

    // ───────────────────────── Меню настроек (/setupvoice) ─────────────────────────

    private String chMention(long id) {
        return id == 0 ? "_не задан_" : "<#" + id + ">";
    }

    private String roleMention(long id) {
        return id == 0 ? "_все участники_" : "<@&" + id + ">";
    }

    private Container buildSettings(Guild guild) {
        Config c = cfg(guild);
        String text = "## ⚙️ Настройка голосовых комнат\n"
                + "**Канал с панелью:** " + chMention(c.panelChannelId) + "\n"
                + "**Войс-создатель:** " + chMention(c.creatorChannelId) + "\n"
                + "**Роль с доступом к меню:** " + roleMention(c.roleId) + "\n"
                + "**Баннер:** " + (c.bannerFile != null ? "загружен ✅" : "нет") + "\n\n"
                + "Чтобы загрузить баннер, вызови `/setupvoice` и прикрепи картинку в поле `banner`.\n"
                + "После любых изменений нажми «Опубликовать панель».";

        return Container.of(
                TextDisplay.of(text),
                Separator.createDivider(Separator.Spacing.SMALL),
                ActionRow.of(EntitySelectMenu.create(SETUP + "panelch", EntitySelectMenu.SelectTarget.CHANNEL)
                        .setChannelTypes(ChannelType.TEXT)
                        .setPlaceholder("📝 Канал для панели").build()),
                ActionRow.of(EntitySelectMenu.create(SETUP + "creator", EntitySelectMenu.SelectTarget.CHANNEL)
                        .setChannelTypes(ChannelType.VOICE)
                        .setPlaceholder("🔊 Войс-создатель").build()),
                ActionRow.of(EntitySelectMenu.create(SETUP + "role", EntitySelectMenu.SelectTarget.ROLE)
                        .setPlaceholder("🎭 Роль с доступом к меню").build()),
                ActionRow.of(
                        Button.success(SETUP + "publish", "📨 Опубликовать панель"),
                        Button.danger(SETUP + "bannerclear", "🗑️ Убрать баннер")
                )
        ).withAccentColor(0x5865F2);
    }

    private void handleSetupButton(ButtonInteractionEvent e) {
        Member m = e.getMember();
        Guild guild = e.getGuild();
        if (m == null || guild == null || !m.hasPermission(Permission.ADMINISTRATOR)) {
            e.reply("❌ Только для администраторов.").setEphemeral(true).queue();
            return;
        }
        Config c = cfg(guild);

        switch (e.getComponentId()) {
            case SETUP + "publish" -> {
                TextChannel tc = c.panelChannelId == 0 ? null : guild.getTextChannelById(c.panelChannelId);
                if (tc == null) {
                    e.reply("❌ Сначала выбери канал для панели.").setEphemeral(true).queue();
                    return;
                }
                if (c.creatorChannelId == 0 || guild.getVoiceChannelById(c.creatorChannelId) == null) {
                    e.reply("❌ Сначала выбери войс-создатель.").setEphemeral(true).queue();
                    return;
                }
                publishPanel(guild, tc, c);
                e.reply("✅ Панель опубликована в " + tc.getAsMention()).setEphemeral(true).queue();
            }
            case SETUP + "bannerclear" -> {
                if (c.bannerFile != null) {
                    try {
                        Files.deleteIfExists(DATA_DIR.resolve(c.bannerFile));
                    } catch (IOException ignored) {
                    }
                    c.bannerFile = null;
                    save();
                }
                e.editComponents(buildSettings(guild)).useComponentsV2().queue();
            }
            default -> e.reply("❌ Неизвестная кнопка.").setEphemeral(true).queue();
        }
    }

    private void handleSetupSelect(EntitySelectInteractionEvent e) {
        Member m = e.getMember();
        Guild guild = e.getGuild();
        if (m == null || guild == null || !m.hasPermission(Permission.ADMINISTRATOR)) {
            e.reply("❌ Только для администраторов.").setEphemeral(true).queue();
            return;
        }
        Config c = cfg(guild);

        switch (e.getComponentId()) {
            case SETUP + "panelch" -> {
                var list = e.getMentions().getChannels();
                if (!list.isEmpty()) c.panelChannelId = list.get(0).getIdLong();
            }
            case SETUP + "creator" -> {
                var list = e.getMentions().getChannels();
                if (!list.isEmpty()) c.creatorChannelId = list.get(0).getIdLong();
            }
            case SETUP + "role" -> {
                var list = e.getMentions().getRoles();
                if (!list.isEmpty()) c.roleId = list.get(0).getIdLong();
            }
            default -> {
            }
        }
        save();
        e.editComponents(buildSettings(guild)).useComponentsV2().queue();
    }

    // ───────────────────────── Панель (Components V2) ─────────────────────────

    private FileUpload loadBanner(Config c) {
        if (c.bannerFile == null) return null;
        Path p = DATA_DIR.resolve(c.bannerFile);
        if (!Files.exists(p)) return null;
        String ext = c.bannerFile.substring(c.bannerFile.lastIndexOf('.'));
        return FileUpload.fromData(p.toFile(), "banner" + ext);
    }

    private Container buildPanel(FileUpload banner) {
        List<ContainerChildComponent> items = new ArrayList<>();

        if (banner != null) {
            items.add(MediaGallery.of(MediaGalleryItem.fromUrl("attachment://" + banner.getName())));
        }

        items.add(TextDisplay.of("## 🎙️ Управление приватным войсом\nЗайди в войс-создатель, затем используй кнопки ниже. Доступно только владельцу канала."));
        items.add(Separator.createDivider(Separator.Spacing.SMALL));
        items.add(ActionRow.of(
                Button.primary(PREFIX + "rename", "✏️ Название"),
                Button.primary(PREFIX + "limit", "👥 Лимит"),
                Button.secondary(PREFIX + "lock", "🔒 Закрыть/Открыть"),
                Button.secondary(PREFIX + "hide", "👁️ Скрыть/Показать")
        ));
        items.add(ActionRow.of(
                Button.success(PREFIX + "invite", "➕ Пригласить"),
                Button.danger(PREFIX + "revoke", "➖ Забрать доступ"),
                Button.danger(PREFIX + "ban", "⛔ Бан"),
                Button.success(PREFIX + "unban", "✅ Разбан")
        ));
        items.add(ActionRow.of(
                Button.danger(PREFIX + "kick", "👢 Кикнуть"),
                Button.secondary(PREFIX + "transfer", "👑 Передать")
        ));

        return Container.of(items).withAccentColor(0x5865F2);
    }

    private void publishPanel(Guild guild, TextChannel channel, Config c) {
        // удаляем прошлую панель
        if (c.publishedMessageId != 0) {
            TextChannel old = guild.getTextChannelById(c.publishedChannelId);
            if (old != null) old.deleteMessageById(c.publishedMessageId).queue(null, err -> {});
        }

        FileUpload banner = loadBanner(c);
        MessageCreateAction action = channel.sendMessageComponents(buildPanel(banner)).useComponentsV2();
        if (banner != null) action = action.setFiles(banner);

        action.queue(msg -> {
            c.publishedChannelId = channel.getIdLong();
            c.publishedMessageId = msg.getIdLong();
            save();
        });
    }

    // ───────────────────────── Создание / удаление каналов ─────────────────────────

    @Override
    public void onGuildVoiceUpdate(GuildVoiceUpdateEvent e) {
        AudioChannelUnion joined = e.getChannelJoined();
        AudioChannelUnion left = e.getChannelLeft();
        Member member = e.getMember();
        Guild guild = e.getGuild();
        Config c = cfg(guild);

        // Зашёл в создатель → создаём канал
        if (joined != null && c.creatorChannelId != 0 && joined.getIdLong() == c.creatorChannelId) {
            Category category = joined.asVoiceChannel().getParentCategory();
            var action = guild.createVoiceChannel(member.getEffectiveName(), category)
                    .addMemberPermissionOverride(member.getIdLong(),
                            List.of(Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT), List.of());

            action.queue(ch -> {
                owners.put(ch.getIdLong(), member.getIdLong());
                guild.moveVoiceMember(member, ch).queue(null, err -> {
                    owners.remove(ch.getIdLong());
                    ch.delete().queue();
                });
            });
        }

        // Вышел из приватного канала
        if (left != null && owners.containsKey(left.getIdLong())) {
            VoiceChannel ch = left.asVoiceChannel();
            List<Member> remaining = ch.getMembers();

            if (remaining.isEmpty()) {
                owners.remove(ch.getIdLong());
                ch.delete().queue(null, err -> {});
            } else if (owners.get(ch.getIdLong()) == member.getIdLong()) {
                // владелец ушёл, но остались люди → авто-передача (у старого права забираются)
                Member next = remaining.stream().filter(m -> !m.getUser().isBot()).findFirst().orElse(remaining.get(0));
                changeOwner(ch, member.getIdLong(), next);
            }
        }
    }

    /** Передача владения: новому выдаём права, у старого владельца личные права забираем. */
    private void changeOwner(VoiceChannel ch, long oldOwnerId, Member newOwner) {
        owners.put(ch.getIdLong(), newOwner.getIdLong());

        ch.upsertPermissionOverride(newOwner)
                .grant(Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT).queue();

        ch.getMemberPermissionOverrides().stream()
                .filter(o -> o.getIdLong() == oldOwnerId)
                .findFirst()
                .ifPresent(o -> o.delete().queue(null, err -> {}));
    }

    // ───────────────────────── Кнопки ─────────────────────────

    @Override
    public void onButtonInteraction(ButtonInteractionEvent e) {
        String id = e.getComponentId();

        if (id.startsWith(SETUP)) {
            handleSetupButton(e);
            return;
        }
        if (!id.startsWith(PREFIX)) return;
        String action = id.substring(PREFIX.length());

        Member member = e.getMember();
        Guild guild = e.getGuild();
        if (member == null || guild == null) return;

        // доступ к меню по роли
        Config c = cfg(guild);
        if (c.roleId != 0 && member.getRoles().stream().noneMatch(r -> r.getIdLong() == c.roleId)) {
            e.reply("❌ У тебя нет роли для использования этого меню: <@&" + c.roleId + ">")
                    .setEphemeral(true).queue();
            return;
        }

        VoiceChannel ch = currentPrivateChannel(member);
        if (ch == null) {
            e.reply("❌ Ты не находишься в приватном войсе.").setEphemeral(true).queue();
            return;
        }
        if (!isOwner(ch, member)) {
            e.reply("❌ Только владелец канала может это делать.").setEphemeral(true).queue();
            return;
        }

        switch (action) {
            case "rename" -> e.replyModal(
                    Modal.create(PREFIX + "rename:modal", "Название канала")
                            .addComponents(Label.of("Новое название",
                                    TextInput.create("name", TextInputStyle.SHORT)
                                            .setMinLength(1).setMaxLength(100).setRequired(true).build()))
                            .build()).queue();

            case "limit" -> e.replyModal(
                    Modal.create(PREFIX + "limit:modal", "Лимит участников")
                            .addComponents(Label.of("Число от 0 до 99 (0 — без лимита)",
                                    TextInput.create("limit", TextInputStyle.SHORT)
                                            .setMinLength(1).setMaxLength(2).setRequired(true).build()))
                            .build()).queue();

            case "lock" -> toggleLock(e, ch);

            case "hide" -> toggleHide(e, ch);

            case "invite", "revoke", "ban", "unban", "kick", "transfer" -> {
                String text = switch (action) {
                    case "invite" -> "Кого пригласить в канал?";
                    case "revoke" -> "У кого забрать доступ?";
                    case "ban" -> "Кого забанить в канале?";
                    case "unban" -> "Кого разбанить?";
                    case "kick" -> "Кого кикнуть из канала?";
                    default -> "Кому передать владение? (ты потеряешь права владельца)";
                };
                EntitySelectMenu menu = EntitySelectMenu
                        .create(PREFIX + action + ":select", EntitySelectMenu.SelectTarget.USER)
                        .setMaxValues(1).build();
                e.replyComponents(TextDisplay.of(text), ActionRow.of(menu))
                        .useComponentsV2().setEphemeral(true).queue();
            }

            default -> e.reply("❌ Эта кнопка устарела. Попроси админа обновить панель (/setupvoice).")
                    .setEphemeral(true).queue();
        }
    }

    private void toggleLock(ButtonInteractionEvent e, VoiceChannel ch) {
        Role everyone = ch.getGuild().getPublicRole();
        if (publicDenies(ch, Permission.VOICE_CONNECT)) {
            ch.upsertPermissionOverride(everyone).clear(Permission.VOICE_CONNECT).queue();
            e.reply("🔓 Канал открыт.").setEphemeral(true).queue();
        } else {
            ch.upsertPermissionOverride(everyone).deny(Permission.VOICE_CONNECT).queue();
            e.reply("🔒 Канал закрыт.").setEphemeral(true).queue();
        }
    }

    private void toggleHide(ButtonInteractionEvent e, VoiceChannel ch) {
        Role everyone = ch.getGuild().getPublicRole();
        if (publicDenies(ch, Permission.VIEW_CHANNEL)) {
            ch.upsertPermissionOverride(everyone).clear(Permission.VIEW_CHANNEL).queue();
            e.reply("👁️ Канал снова виден всем.").setEphemeral(true).queue();
        } else {
            ch.upsertPermissionOverride(everyone).deny(Permission.VIEW_CHANNEL).queue();
            e.reply("🙈 Канал скрыт. Его видят только владелец и те, кому ты дал доступ.").setEphemeral(true).queue();
        }
    }

    // ───────────────────────── Выбор пользователя ─────────────────────────

    @Override
    public void onEntitySelectInteraction(EntitySelectInteractionEvent e) {
        String id = e.getComponentId();

        if (id.startsWith(SETUP)) {
            handleSetupSelect(e);
            return;
        }
        if (!id.startsWith(PREFIX) || !id.endsWith(":select")) return;
        String action = id.substring(PREFIX.length(), id.length() - ":select".length());

        Member owner = e.getMember();
        if (owner == null) return;

        VoiceChannel ch = currentPrivateChannel(owner);
        if (ch == null || !isOwner(ch, owner)) {
            e.reply("❌ Ты не владелец приватного канала.").setEphemeral(true).queue();
            return;
        }

        List<Member> picked = e.getMentions().getMembers();
        if (picked.isEmpty()) {
            e.reply("❌ Пользователь не найден на сервере.").setEphemeral(true).queue();
            return;
        }
        Member target = picked.get(0);

        if (target.getIdLong() == owner.getIdLong()) {
            e.reply("❌ Нельзя выбрать самого себя.").setEphemeral(true).queue();
            return;
        }
        if (target.getUser().isBot()) {
            e.reply("❌ Нельзя выбрать бота.").setEphemeral(true).queue();
            return;
        }

        switch (action) {
            case "invite" -> {
                ch.upsertPermissionOverride(target)
                        .grant(Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT).queue();
                e.reply("➕ " + target.getAsMention() + " получил доступ (даже если канал закрыт или скрыт).")
                        .setEphemeral(true).queue();
            }
            case "revoke" -> {
                PermissionOverride po = ch.getPermissionOverride(target);
                boolean hasAccess = po != null && (po.getAllowed().contains(Permission.VOICE_CONNECT)
                        || po.getAllowed().contains(Permission.VIEW_CHANNEL));
                if (!hasAccess) {
                    e.reply("❌ У " + target.getAsMention() + " нет выданного доступа.").setEphemeral(true).queue();
                    return;
                }
                ch.upsertPermissionOverride(target)
                        .clear(Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT).queue();

                // если канал закрыт/скрыт — человек больше не сможет остаться, выкидываем
                boolean restricted = publicDenies(ch, Permission.VOICE_CONNECT)
                        || publicDenies(ch, Permission.VIEW_CHANNEL);
                if (restricted) kickIfInChannel(target, ch);

                e.reply("➖ Доступ у " + target.getAsMention() + " забран.").setEphemeral(true).queue();
            }
            case "ban" -> {
                ch.upsertPermissionOverride(target).deny(Permission.VOICE_CONNECT).queue();
                kickIfInChannel(target, ch);
                e.reply("⛔ " + target.getAsMention() + " забанен в канале.").setEphemeral(true).queue();
            }
            case "unban" -> {
                ch.upsertPermissionOverride(target).clear(Permission.VOICE_CONNECT).queue();
                e.reply("✅ " + target.getAsMention() + " разбанен.").setEphemeral(true).queue();
            }
            case "kick" -> {
                if (!kickIfInChannel(target, ch)) {
                    e.reply("❌ Этого пользователя нет в твоём канале.").setEphemeral(true).queue();
                    return;
                }
                e.reply("👢 " + target.getAsMention() + " кикнут.").setEphemeral(true).queue();
            }
            case "transfer" -> {
                changeOwner(ch, owner.getIdLong(), target);
                e.reply("👑 Новый владелец: " + target.getAsMention() + ". Твои права владельца сняты.")
                        .setEphemeral(true).queue();
            }
        }
    }

    // ───────────────────────── Модалки ─────────────────────────

    @Override
    public void onModalInteraction(ModalInteractionEvent e) {
        String id = e.getModalId();
        if (!id.startsWith(PREFIX)) return;

        Member member = e.getMember();
        if (member == null) return;

        VoiceChannel ch = currentPrivateChannel(member);
        if (ch == null || !isOwner(ch, member)) {
            e.reply("❌ Ты не владелец приватного канала.").setEphemeral(true).queue();
            return;
        }

        switch (id) {
            case PREFIX + "rename:modal" -> {
                String name = e.getValue("name").getAsString().trim();
                ch.getManager().setName(name).queue();
                e.reply("✏️ Название изменено на **" + name + "**").setEphemeral(true).queue();
            }
            case PREFIX + "limit:modal" -> {
                int limit;
                try {
                    limit = Integer.parseInt(e.getValue("limit").getAsString().trim());
                } catch (NumberFormatException ex) {
                    e.reply("❌ Введи число от 0 до 99.").setEphemeral(true).queue();
                    return;
                }
                if (limit < 0 || limit > 99) {
                    e.reply("❌ Введи число от 0 до 99.").setEphemeral(true).queue();
                    return;
                }
                ch.getManager().setUserLimit(limit).queue();
                e.reply("👥 Лимит: " + (limit == 0 ? "без ограничений" : limit)).setEphemeral(true).queue();
            }
        }
    }

    // ───────────────────────── Хелперы ─────────────────────────

    /** Приватный канал, в котором сейчас сидит участник (или null). */
    private VoiceChannel currentPrivateChannel(Member member) {
        var state = member.getVoiceState();
        if (state == null || state.getChannel() == null) return null;
        AudioChannelUnion ch = state.getChannel();
        return owners.containsKey(ch.getIdLong()) ? ch.asVoiceChannel() : null;
    }

    private boolean isOwner(VoiceChannel ch, Member member) {
        Long ownerId = owners.get(ch.getIdLong());
        return ownerId != null && ownerId == member.getIdLong();
    }

    /** Запрещено ли право для @everyone в этом канале. */
    private boolean publicDenies(VoiceChannel ch, Permission perm) {
        PermissionOverride o = ch.getPermissionOverride(ch.getGuild().getPublicRole());
        return o != null && o.getDenied().contains(perm);
    }

    private boolean kickIfInChannel(Member target, VoiceChannel ch) {
        var st = target.getVoiceState();
        if (st != null && st.getChannel() != null && st.getChannel().getIdLong() == ch.getIdLong()) {
            ch.getGuild().kickVoiceMember(target).queue();
            return true;
        }
        return false;
    }
}
