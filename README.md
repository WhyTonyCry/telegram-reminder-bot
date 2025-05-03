# ⏰ Telegram Reminder Bot

> **Telegram Reminder Bot** is a user-friendly Java-based Telegram bot designed to create and manage personal reminders across multiple time zones relative to Moscow time (GMT+3).

---

## 🚀 Features

- **🕑 Time Zone Customization**
  - Set your local time offset relative to Moscow.
  - Automatic time conversion.

- **🔔 Reminder Management**
  - Create reminders quickly and easily.
  - Reminders automatically delete upon completion.

- **📃 View Active Reminders**
  - Clearly view your reminders in local time.
  - Interactive inline-button interface.

- **🗑️ Flexible Deletion**
  - Delete reminders individually or all at once.

---

## 📦 Project Structure

| Class/File          | Description                                           |
|---------------------|-------------------------------------------------------|
| `App.java`          | Main class; initializes and registers the bot.        |
| `Bot.java`          | Handles core bot logic: messages, reminders, callbacks. |
| `UserState`         | Manages user-specific data (time offset, reminders).  |
| `Reminder`          | Represents reminders (content, timing, auto-deletion).|

---

## ⚙️ How It Works

### 1. **Set Your Time Offset**

- Users first enter their local time offset from Moscow (e.g., `-1`, `+2`).
- The bot stores this offset for accurate reminder timing.

### 2. **Create Reminders**

- Enter reminder text and local time (`HH:mm`).
- Bot converts to Moscow time and schedules the reminder.
- If the chosen time has passed, it automatically schedules for the next day.

### 3. **Receive Notifications**

- Users get timely reminders via Telegram messages.
- Reminders remove themselves automatically after notification.

---

## 🎯 Commands & Actions

| Command / Action           | Description                                        |
|----------------------------|----------------------------------------------------|
| `/start`                   | Start interaction or set time offset.              |
| **Create Reminder**        | Start creating a new reminder.                     |
| **List Reminders**         | View all your scheduled reminders.                 |
| **Delete All Reminders**   | Remove all active reminders.                       |
| **Delete Individual**      | Selectively delete individual reminders.           |

---

## 🛠 Technology Stack

- **Java 17**
- **TelegramBots API**
- **Maven** (Dependency Management)

---

## 🚩 Example Interaction
User: /start Bot: 👋 Hello, Alex! Please specify your time offset from Moscow. (e.g., -1, +2, +7)

User: +1 Bot: ✅ Offset from Moscow (+1 hr) saved. [Create Reminder] [List Reminders] [Delete All] [Delete Individual]

User (creates reminder): Take medication at 21:00 Bot: ✅ Reminder "Take medication" set for 21:00 ⏳


---

## 📌 Setup & Deployment

**1. Clone the repository:**
```bash
git clone <your_repository_url>
```
**2. Replace the bot token in Bot.java:
```java
@Override
public String getBotToken() {
    return "YOUR_BOT_TOKEN";
}
```
**3. Build and run via Maven:
```bash
mvn clean compile assembly:single
java -jar target/your-bot-name.jar
```

## 📖 Maven Dependency
```xml
<!-- TelegramBots -->
<dependency>
    <groupId>org.telegram</groupId>
    <artifactId>telegrambots</artifactId>
    <version>6.9.7.0</version>
</dependency>
```
## ⭐ Telegram Reminder Bot helps you effortlessly manage personal reminders directly in Telegram, ensuring you never miss a task, regardless of your time zone!

[![Views](https://komarev.com/ghpvc/?username=WhyTonyCry&color=blueviolet&style=for-the-badge&label=Views)](https://github.com/WhyTonyCry)
