package ru.whensclass.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.layout.Alignment
import androidx.glance.layout.Row
import androidx.glance.text.Text
import androidx.glance.text.TextStyle

/**
 * Вторая строка шапки виджета: группа, время проверки, пометка неполного дня и ⟳.
 * Одна на недельный и дневной: расходились цвет значка при сбое и пометка «не прочитан».
 */
@Composable
internal fun StatusLine(
    groupName: String?,
    fetchedAt: Long,
    nowMillis: Long,
    busy: Boolean,
    done: Boolean,
    failed: Boolean,
    serverBroken: Boolean,
    editing: Boolean,
    gone: Boolean,
    unsupported: Boolean,
    unread: Boolean,
    colors: Palette,
    openApp: Action,
    /** Тихая пометка графика: «сессия», «каникулы», «практика»; null — график обычный. */
    state: String? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            // ФИО целиком не влезает рядом со временем проверки.
            groupName?.let { if (it.count { c -> c == ' ' } >= 2) shortenName(it) else it }.orEmpty(),
            maxLines = 1,
            style = TextStyle(fontSize = 11.sp, color = colors.textDim),
            // Имя уступает место: статус и ⟳ меряются первыми, иначе на узком виджете режутся.
            modifier = GlanceModifier.defaultWeight().clickable(openApp),
        )
        // График колледжа — второй, после имени: день с парами не прячет ни пар, ни сессии.
        if (state != null) {
            Text(
                " · $state",
                maxLines = 1,
                style = TextStyle(fontSize = 11.sp, color = colors.textDim),
                modifier = GlanceModifier.clickable(openApp),
            )
        }
        // Пометка дня — здесь, а не у даты: рядом со стрелками ей не хватало места.
        if (unread) {
            Text(
                " · не прочитан",
                maxLines = 1,
                style = TextStyle(fontSize = 11.sp, color = colors.textDim),
                modifier = GlanceModifier.clickable(openApp),
            )
        }
        // Время последней проверки — служебная мелочь: приглушённым цветом, краснеет только
        // когда данные протухли.
        Text(
            when {
                busy -> " · обновление…"
                failed -> " · не обновилось"
                // Пропажа и сбой — не «обновлено»: ответ пришёл, но расписание в нём прежнее.
                gone -> " · нет в таблице"
                unsupported -> " · обновите приложение"
                // Сбой показываем и когда пары на экране есть (снимок прежний); коротко —
                // подробности на плашке в приложении.
                serverBroken -> if (editing) " · правка таблицы" else " · сбой"
                done -> " · обновлено"
                else -> " · " + formatFetchedShort(fetchedAt, nowMillis)
            },
            maxLines = 1,
            style = TextStyle(
                fontSize = 11.sp,
                color = when {
                    failed || (serverBroken && !editing) || gone || unsupported -> colors.error
                    busy || done -> colors.accent
                    else -> colors.textDim
                },
            ),
            // Группы нет в таблице — в приложение, к «выбрать заново».
            modifier = GlanceModifier.clickable(
                if (gone || unsupported) openApp else actionRunCallback<RefreshAction>(),
            ),
        )
        // Отдельным текстом, а не хвостом времени: внутри одного текста значок встаёт по
        // базовой линии и висит выше цифр. Показывается всегда: иначе высота строки меняется.
        Text(
            " ⟳",
            maxLines = 1,
            style = TextStyle(
                fontSize = 11.sp,
                color = when {
                    failed -> colors.error
                    busy || done -> colors.accent
                    else -> colors.textDim
                },
            ),
            modifier = GlanceModifier.clickable(actionRunCallback<RefreshAction>()),
        )
    }
}
