package org.example;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.*;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Бот:
 *  1) Считает базовым время Москвы (Europe/Moscow).
 *  2) Пользователь вводит offset относительно Москвы (например, -1, +2).
 *  3) Создаёт напоминания, которые автоматически удаляются из списка по срабатыванию.
 *  4) Можно удалить все разом или выборочно каждое.
 */
public class Bot extends TelegramLongPollingBot {

    // Храним состояние каждого пользователя: offset от Москвы, список напоминаний, текущее "State"
    private final Map<Long, UserState> userStates = new HashMap<>();

    @Override
    public String getBotUsername() {
        return "Reminder_it_bot";
    }

    @Override
    public String getBotToken() {
        return "ur code";
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasCallbackQuery()) {
            handleCallback(update.getCallbackQuery());
        } else if (update.hasMessage()) {
            handleMessage(update.getMessage());
        }
    }

    /**
     * Обработка обычных сообщений (текстовых).
     */
    private void handleMessage(Message message) {
        long chatId = message.getChatId();
        String text = message.getText();

        // Создаём/получаем состояние пользователя
        UserState userState = userStates.computeIfAbsent(chatId, k -> new UserState());

        // Если команда /start
        if ("/start".equals(text)) {
            if (userState.hasOffsetFromMoscow()) {
                // Если уже знаем сдвиг относительно Москвы, сразу меню
                sendMainMenu(chatId, userState);
            } else {
                // Иначе просим ввести offset
                userState.setState(State.WAITING_FOR_OFFSET);
                sendText(chatId,
                        "Привет , " + message.getChat().getFirstName() + " \uD83D\uDC4B \n" +
                                "Укажи разницу с московским временем (в часах). Например, если у тебя -1 (Калининград), " +
                                "или +1 (Самара), или +7 (Хабаровск) и т.д.");
            }
            return;
        }

        switch (userState.getState()) {
            case WAITING_FOR_OFFSET:
                // Парсим сдвиг от Москвы (целое число)
                try {
                    int offset = Integer.parseInt(text.trim());
                    userState.setOffsetFromMoscow(offset);
                    userState.setState(State.NONE);

                    sendText(chatId, "Сдвиг от Москвы (" + offset + " ч.) сохранён ✅");
                    sendMainMenu(chatId, userState);

                } catch (NumberFormatException e) {
                    sendText(chatId, "Не понял, введите целое число (например, -1, +2).");
                }
                break;

            case ENTERING_REMINDER_TEXT:
                // Пользователь ввёл текст напоминания
                userState.setTempReminderText(text);
                userState.setState(State.ENTERING_REMINDER_TIME);
                sendText(chatId, "Когда напомнить? Формат: HH:mm");
                break;

            case ENTERING_REMINDER_TIME:
                // Парсим время, переводим в московское, ставим таймер
                createReminder(chatId, userState, text);
                break;

            default:
                // Вне сценария - просто показываем меню или просим указать offset
                if (!userState.hasOffsetFromMoscow()) {
                    userState.setState(State.WAITING_FOR_OFFSET);
                    sendText(chatId, "Сначала укажи разницу от Москвы (например, -1).");
                } else {
                    sendMainMenu(chatId, userState);
                }
                break;
        }
    }

    /**
     * Обработка нажатия на инлайн-кнопки
     */
    private void handleCallback(CallbackQuery callbackQuery) {
        String data = callbackQuery.getData();
        long chatId = callbackQuery.getMessage().getChatId();

        UserState userState = userStates.computeIfAbsent(chatId, k -> new UserState());

        // Если ещё не знаем offset
        if (!userState.hasOffsetFromMoscow()) {
            userState.setState(State.WAITING_FOR_OFFSET);
            sendText(chatId, "Сначала укажи разницу от Москвы (например, -1 или +2).");
            return;
        }

        switch (data) {
            case "createReminder":
                userState.setState(State.ENTERING_REMINDER_TEXT);
                sendText(chatId, "О чём необходимо напомнить?");
                break;

            case "listReminders":
                listReminders(chatId, userState);
                sendMainMenu(chatId, userState);
                break;

            case "deleteAllReminders":
                userState.getReminders().clear();
                sendText(chatId, "Все напоминания удалены ✅");
                sendMainMenu(chatId, userState);
                break;

            case "deleteOneReminder":
                // Покажем пользователю список с кнопками "Удалить #N"
                showDeleteMenu(chatId, userState);
                break;

            case "backToMenu":
                sendMainMenu(chatId, userState);
                break;

            default:
                // Может быть колбэк вида "deleteSingle_<id>"
                if (data.startsWith("deleteSingle_")) {
                    String idStr = data.substring("deleteSingle_".length());
                    deleteSingleReminder(chatId, userState, idStr);
                } else {
                    // Неизвестный колбэк
                    sendMainMenu(chatId, userState);
                }
        }
    }

    // ------------------------------------------------------------------------------------
    // ЛОГИКА создания напоминания: парсим время, считаем задержку, ставим таймер, сохраняем
    // ------------------------------------------------------------------------------------
    private void createReminder(long chatId, UserState userState, String userInputTime) {
        try {
            // Парсим HH:mm. По умолчанию SimpleDateFormat возьмёт таймзону сервера,
            // но мы в любом случае берём только часы/минуты, так что неважно.
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm");
            Date localTime = sdf.parse(userInputTime);

            // Текущее время (Москва)
            Calendar nowMoscow = Calendar.getInstance(TimeZone.getTimeZone("Europe/Moscow"));
            long nowMillis = nowMoscow.getTimeInMillis();

            // Календарь для "московского" времени срабатывания
            Calendar reminderMoscow = Calendar.getInstance(TimeZone.getTimeZone("Europe/Moscow"));
            reminderMoscow.setTimeInMillis(nowMillis);

            // Устанавливаем часы/минуты, которые ввёл пользователь (локальное для него),
            // а затем вычитаем offset, чтобы получить московское время
            reminderMoscow.set(Calendar.HOUR_OF_DAY, localTime.getHours());
            reminderMoscow.set(Calendar.MINUTE, localTime.getMinutes());
            reminderMoscow.set(Calendar.SECOND, 0);
            reminderMoscow.set(Calendar.MILLISECOND, 0);

            // offsetFromMoscow: (local = moscow + offset)
            // => moscow = local - offset
            reminderMoscow.add(Calendar.HOUR_OF_DAY, -userState.getOffsetFromMoscow());

            // Если уже прошёл момент, переносим на завтра
            if (reminderMoscow.getTimeInMillis() < nowMillis) {
                reminderMoscow.add(Calendar.DAY_OF_MONTH, 1);
            }

            long delay = reminderMoscow.getTimeInMillis() - nowMillis;

            String text = userState.getTempReminderText();
            userState.setTempReminderText(null);
            userState.setState(State.NONE);

            // Удобно показать пользователю, когда это в его локальном времени (для проверки)
            Calendar localCal = (Calendar) reminderMoscow.clone();
            localCal.add(Calendar.HOUR_OF_DAY, userState.getOffsetFromMoscow());
            String localTimeStr = sdf.format(localCal.getTime());

            // Показываем, что напоминание установлено
            sendText(chatId, "Напоминание «" + text + "» установлено" +
                    " на " + localTimeStr + "\n ⏳"
            );

            // Создаём объект напоминания
            Reminder reminder = new Reminder(
                    UUID.randomUUID().toString(), // уникальный id
                    text,
                    reminderMoscow.getTimeInMillis()
            );

            // Ставим таймер (при срабатывании автоматически удалить из списка)
            Timer timer = new Timer();
            reminder.setTimer(timer);

            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    // 1) Отправляем сообщение
                    sendText(chatId, "  ⚠\uFE0F Не забудь про: " + reminder.getText());

                    // 2) Удаляем это напоминание из списка, чтобы не висело
                    userState.getReminders().remove(reminder);

                    sendMainMenu(chatId, userState);
                }
            }, delay);

            // Сохраняем напоминание в UserState
            userState.getReminders().add(reminder);

            // Показываем меню
            sendMainMenu(chatId, userState);

        } catch (ParseException e) {
            sendText(chatId, "Неверный формат времени, введите HH:mm, например 22:15");
        }
    }

    // ------------------------------------------------------------------------------------
    // ПОКАЗ списка напоминаний
    // ------------------------------------------------------------------------------------
    private void listReminders(long chatId, UserState userState) {
        List<Reminder> list = userState.getReminders();
        if (list.isEmpty()) {
            sendText(chatId, "У вас нет ни одного напоминания");
            return;
        }
        // Хотим показывать локальное время пользователя
        int offset = userState.getOffsetFromMoscow();

        StringBuilder sb = new StringBuilder("💥 Ваши напоминания:\n\n");
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm"); // формат "ЧЧ:мм"

        for (int i = 0; i < list.size(); i++) {
            Reminder r = list.get(i);

            // У нас r.getMoscowMillis() - это время по Москве
            // Переведём его в локальное время:
            Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Europe/Moscow"));
            cal.setTimeInMillis(r.getMoscowMillis());

            // Добавляем offset (если пользователь +1, то "местное" = "москва" + 1)
            cal.add(Calendar.HOUR_OF_DAY, offset);

            // Форматируем:
            String localTimeStr = sdf.format(cal.getTime());

            sb.append(i + 1).append(") ")
                    .append(r.getText())
                    .append(" (").append(localTimeStr).append(")")
                    .append("\n");
        }

        sendText(chatId, sb.toString());
    }

    // ------------------------------------------------------------------------------------
    // МЕНЮ для удаления ОДНОГО напоминания
    // ------------------------------------------------------------------------------------
    private void showDeleteMenu(long chatId, UserState userState) {
        List<Reminder> list = userState.getReminders();
        if (list.isEmpty()) {
            sendText(chatId, "У вас нет напоминаний для удаления.");
            sendMainMenu(chatId, userState);
            return;
        }

        // Готовим сообщение
        SendMessage msg = new SendMessage();
        msg.setChatId(String.valueOf(chatId));
        msg.setText("Выберите, какое напоминание удалить:");

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        // Кнопка для каждого напоминания
        for (int i = 0; i < list.size(); i++) {
            Reminder r = list.get(i);
            String shortText = r.getText();
            if (shortText.length() > 30) {
                shortText = shortText.substring(0, 30) + "...";
            }
            InlineKeyboardButton btn = new InlineKeyboardButton(
                    "Удалить #" + (i + 1) + " (" + shortText + ")"
            );
            // callbackData = "deleteSingle_ID"
            btn.setCallbackData("deleteSingle_" + r.getId());
            rows.add(Collections.singletonList(btn));
        }

        // Добавим кнопку "Назад в меню"
        InlineKeyboardButton backBtn = new InlineKeyboardButton("Отмена / Назад");
        backBtn.setCallbackData("backToMenu");
        rows.add(Collections.singletonList(backBtn));

        markup.setKeyboard(rows);
        msg.setReplyMarkup(markup);

        try {
            execute(msg);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    /**
     * Удалить одно напоминание по ID, пришедшему в колбэке (deleteSingle_...).
     */
    private void deleteSingleReminder(long chatId, UserState userState, String reminderId) {
        // Ищем напоминание в списке
        List<Reminder> list = userState.getReminders();
        Reminder toRemove = null;

        for (Reminder r : list) {
            if (r.getId().equals(reminderId)) {
                toRemove = r;
                break;
            }
        }
        if (toRemove == null) {
            sendText(chatId, "Не нашли напоминание для удаления (ID=" + reminderId + ").");
            showDeleteMenu(chatId, userState);
            return;
        }

        // Отменим таймер, если ещё не сработал
        if (toRemove.getTimer() != null) {
            toRemove.getTimer().cancel();
        }

        list.remove(toRemove);

        sendText(chatId, "Напоминание «" + toRemove.getText() + "» удалено ✅");
        showDeleteMenu(chatId, userState);
    }

    // ------------------------------------------------------------------------------------
    // ГЛАВНОЕ МЕНЮ (кнопки: Создать, Список, Удалить все, Удалить выборочно)
    // ------------------------------------------------------------------------------------
    private void sendMainMenu(long chatId, UserState userState) {
        SendMessage msg = new SendMessage();
        msg.setChatId(String.valueOf(chatId));
        msg.setText(" ⏰ Запланировано напоминаний: " + userState.getReminders().size());

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        // 1) Создать напоминание
        InlineKeyboardButton btnCreate = new InlineKeyboardButton(" \uD83D\uDCCC Создать напоминание");
        btnCreate.setCallbackData("createReminder");
        rows.add(Collections.singletonList(btnCreate));

        // 2) Проверить список
        InlineKeyboardButton btnList = new InlineKeyboardButton(" \uD83D\uDDC2 Проверить список");
        btnList.setCallbackData("listReminders");
        rows.add(Collections.singletonList(btnList));

        // 3) Удалить все
        InlineKeyboardButton btnDeleteAll = new InlineKeyboardButton(" \uD83D\uDDD1 Удалить все напоминания");
        btnDeleteAll.setCallbackData("deleteAllReminders");
        rows.add(Collections.singletonList(btnDeleteAll));

        // 4) Удалить выборочно
        InlineKeyboardButton btnDeleteOne = new InlineKeyboardButton(" ⛓\uFE0F\u200D\uD83D\uDCA5 \uD83D\uDDD1 Удалить выборочно напоминание");
        btnDeleteOne.setCallbackData("deleteOneReminder");
        rows.add(Collections.singletonList(btnDeleteOne));

        markup.setKeyboard(rows);
        msg.setReplyMarkup(markup);

        try {
            execute(msg);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    /**
     * Утилитный метод для отправки текста
     */
    private void sendText(long chatId, String text) {
        try {
            execute(new SendMessage(String.valueOf(chatId), text));
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    // ------------------------------------------------------------------------------------
    // Вспомогательные классы
    // ------------------------------------------------------------------------------------

    /**
     * Состояния диалогов
     */
    public enum State {
        NONE,
        WAITING_FOR_OFFSET,       // ждём, когда пользователь введёт смещение от Москвы
        ENTERING_REMINDER_TEXT,   // ждём, когда введёт текст напоминания
        ENTERING_REMINDER_TIME    // ждём, когда введёт время напоминания
    }

    /**
     * Состояние пользователя
     */
    public static class UserState {
        private State state = State.NONE;
        private Integer offsetFromMoscow; // Сдвиг в часах от московского времени
        private String tempReminderText;  // Временное хранение текста напоминания
        private final List<Reminder> reminders = new ArrayList<>();

        // Getters / setters
        public State getState() {
            return state;
        }
        public void setState(State state) {
            this.state = state;
        }

        public Integer getOffsetFromMoscow() {
            return offsetFromMoscow;
        }
        public void setOffsetFromMoscow(Integer offsetFromMoscow) {
            this.offsetFromMoscow = offsetFromMoscow;
        }
        public boolean hasOffsetFromMoscow() {
            return offsetFromMoscow != null;
        }

        public String getTempReminderText() {
            return tempReminderText;
        }
        public void setTempReminderText(String tempReminderText) {
            this.tempReminderText = tempReminderText;
        }

        public List<Reminder> getReminders() {
            return reminders;
        }
    }

    /**
     * Класс, описывающий одно напоминание
     */
    public static class Reminder {
        private final String id;         // Уникальный идентификатор (например, UUID)
        private final String text;       // Текст, что напомнить
        private final long moscowMillis; // Время срабатывания в московском локальном времени (миллис)
        private Timer timer;            // Ссылка на таймер, чтобы можно было отменить

        public Reminder(String id, String text, long moscowMillis) {
            this.id = id;
            this.text = text;
            this.moscowMillis = moscowMillis;
        }

        public String getId() {
            return id;
        }

        public String getText() {
            return text;
        }

        public long getMoscowMillis() {
            return moscowMillis;
        }

        public Timer getTimer() {
            return timer;
        }

        public void setTimer(Timer timer) {
            this.timer = timer;
        }
    }
}
