package com.dillway

import com.dillway.model.CheatVerdict
import com.dillway.model.InferenceReport
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import java.util.Locale

/**
 * Chat rendering for one [InferenceReport].
 *
 * Shared by `/dill rec predict` and `/dill watch`, so both use the same
 * ordering, threshold and verdict lines. [kind] is the header word that tells
 * the admin which flow produced the numbers.
 */
object PredictionView {

    fun render(
        kind: String,
        subject: String,
        report: InferenceReport,
        threshold: Double,
    ): Component {
        val rows = report.verdicts.sortedWith(
            compareByDescending<CheatVerdict> { it.isFlagged(threshold) }
                .thenByDescending { it.maxProbability },
        )
        val flagged = rows.count { it.isFlagged(threshold) }
        val thresholdText = percent(threshold).removeSuffix(".0")

        val text = Component.text()
            .append(
                mini(
                    "<gray><kind> <dark_gray>·</dark_gray> <yellow><subject></yellow> " +
                        "<dark_gray>·</dark_gray> <aqua><packets></aqua> packets " +
                        "<dark_gray>·</dark_gray> <aqua><models></aqua> models</gray>",
                    Placeholder.unparsed("kind", kind),
                    Placeholder.unparsed("subject", subject),
                    Placeholder.unparsed("packets", count(report.movementPackets)),
                    Placeholder.unparsed("models", report.modelCount.toString()),
                ),
            )

        // The headline answers the only question the admin asks first: did
        // anything trip the threshold?
        text.append(Component.newline()).append(
            if (flagged == 0) {
                mini(
                    "<green>No model flagged</green> <dark_gray>· threshold <threshold>%</dark_gray>",
                    Placeholder.unparsed("threshold", thresholdText),
                )
            } else {
                mini(
                    "<red><flagged> of <models> flagged</red> " +
                        "<dark_gray>· threshold <threshold>%</dark_gray>",
                    Placeholder.unparsed("flagged", flagged.toString()),
                    Placeholder.unparsed("models", report.modelCount.toString()),
                    Placeholder.unparsed("threshold", thresholdText),
                )
            },
        )

        for (verdict in rows) {
            text.append(Component.newline()).append(verdictLine(verdict, threshold))
        }

        for (failure in report.failures) {
            text.append(Component.newline()).append(
                mini("<red>! <failure></red>", Placeholder.unparsed("failure", failure)),
            )
        }

        return text.build()
    }

    /**
     * One line of live state for the action bar.
     *
     * The action bar cannot wrap, so this shows a single model: the flagged
     * one with the highest peak, or the highest peak overall when nothing is
     * flagged. A count of the other flagged models keeps the rest visible
     * without a second line.
     */
    fun liveLine(subject: String, report: InferenceReport, threshold: Double): Component {
        val flagged = report.verdicts.filter { it.isFlagged(threshold) }
        val top = flagged.maxByOrNull { it.maxProbability }
            ?: report.verdicts.filter { it.windows > 0 }.maxByOrNull { it.maxProbability }

        if (top == null) {
            return Component.text()
                .append(Component.text(subject, NamedTextColor.YELLOW))
                .append(dot())
                .append(Component.text("warming up", NamedTextColor.DARK_GRAY))
                .build()
        }

        val text = Component.text()
            .append(Component.text(subject, NamedTextColor.YELLOW))
            .append(dot())
            .append(Component.text(top.cheat, NamedTextColor.GRAY))
            .append(Component.text(" "))
            .append(
                Component.text(
                    "${percent(top.maxProbability)}%",
                    if (flagged.isEmpty()) NamedTextColor.WHITE else NamedTextColor.RED,
                ),
            )

        if (flagged.size > 1) {
            text.append(dot())
                .append(Component.text("${flagged.size} flagged", NamedTextColor.RED))
        }
        return text.build()
    }

    /**
     * One chat line for a [VerdictChange]. [entered] are the cheats that
     * crossed the threshold, [cleared] those that fell back under it.
     */
    fun changeLine(
        subject: String,
        report: InferenceReport,
        entered: Set<String>,
        cleared: Set<String>,
    ): Component {
        val text = Component.text().append(
            mini(
                "<gray>Watch <dark_gray>·</dark_gray> <yellow><subject></yellow></gray>",
                Placeholder.unparsed("subject", subject),
            ),
        )

        for (cheat in entered.sorted()) {
            val verdict = report.verdicts.firstOrNull { it.cheat == cheat } ?: continue
            text.append(dot()).append(
                Component.text(
                    "$cheat flagged ${percent(verdict.maxProbability)}%",
                    NamedTextColor.RED,
                ),
            )
        }

        for (cheat in cleared.sorted()) {
            text.append(dot())
                .append(Component.text("$cheat cleared", NamedTextColor.GREEN))
        }

        return text.build()
    }

    /** One model per line: verdict, name, then diagnostics, dimmest last. */
    private fun verdictLine(verdict: CheatVerdict, threshold: Double): Component {
        val flagged = verdict.isFlagged(threshold)
        val text = Component.text()
            .append(Component.text("  "))
            .append(
                Component.text(
                    if (flagged) "FLAGGED" else "clear",
                    if (flagged) NamedTextColor.RED else NamedTextColor.GRAY,
                ),
            )
            .append(Component.text(" "))
            .append(
                Component.text(verdict.cheat, NamedTextColor.WHITE).hoverEvent(
                    HoverEvent.showText(
                        mini(
                            "model <version> <dark_gray>·</dark_gray> <window>s window",
                            Placeholder.unparsed("version", verdict.version),
                            Placeholder.unparsed("window", number(verdict.windowSeconds)),
                        ),
                    ),
                ),
            )
            .append(Component.text("  "))

        if (verdict.windows == 0) {
            return text.append(
                Component.text("no usable windows", NamedTextColor.DARK_GRAY),
            ).build()
        }

        return text
            .append(
                Component.text(
                    "${percent(verdict.maxProbability)}% peak",
                    if (flagged) NamedTextColor.RED else NamedTextColor.WHITE,
                ),
            )
            .append(dot())
            .append(
                Component.text("avg ${percent(verdict.meanProbability)}%", NamedTextColor.GRAY),
            )
            .append(dot())
            .append(
                Component.text("${verdict.windows} windows", NamedTextColor.GRAY),
            )
            .build()
    }

    private fun percent(value: Double): String =
        String.format(Locale.ROOT, "%.1f", value * 100.0)
}
