# kotlinx.serialization: правила для @Serializable приходят с самой
# библиотекой (kotlinx-serialization-common.pro). Свои дублировали их и
# держали Companion у классов, которые не сериализуются.

# Обработчики нажатий в виджете.
#
# Glance создаёт их отражением:
#   Class.forName(имя).getDeclaredConstructor().newInstance()
# (см. RunCallbackAction$Companion в glance-appwidget).
#
# Правило, которое приходит с самой библиотекой, сохраняет только ИМЯ класса:
#   -keep public class * extends androidx.glance.appwidget.action.ActionCallback
# Конструктор при этом вырезается — в коде эти классы нигде не создаются явно,
# и R8 считает его недостижимым. В результате в собранном приложении нажатия
# на виджете молча не срабатывают: ни переключение дня, ни копирование ссылки.
-keep class * extends androidx.glance.appwidget.action.ActionCallback {
    <init>();
}
