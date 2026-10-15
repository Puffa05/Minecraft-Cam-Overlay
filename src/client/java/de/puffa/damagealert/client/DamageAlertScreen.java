package de.puffa.damagealert.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.puffa.damagealert.Effect;
import de.puffa.damagealert.EffectController;
import de.puffa.damagealert.StreamerBotClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Ingame-Menue (Standard-Taste J): Effekte an/aus schalten und einmal testen.
 * Nutzt denselben {@link EffectController} wie die Weboberflaeche, beide bleiben also synchron.
 * Reine Client-Anzeige: es werden keine Pakete an den Server geschickt.
 */
public class DamageAlertScreen extends Screen {
	private static final int ROW = 24;
	private static final int BUTTON_H = 20;
	private static final int TEST_W = 44;
	private static final int GAP = 4;
	private static final Effect[] STATES = {
			Effect.UNDERWATER, Effect.RAINING,
			Effect.IN_LAVA, Effect.THUNDERING,
			Effect.FREEZE, Effect.BIOME,
			Effect.DIMENSION, Effect.Y};

	private final Screen parent;
	private final EffectController controller;
	private final String webUrl;

	/** Alle Buttons nach Id ("toggle.damage", "test.portalStart", ...), auch fuer den automatischen Test. */
	private final Map<String, ButtonWidget> buttons = new LinkedHashMap<>();
	private final List<Effect> toggleOrder = new ArrayList<>();

	private Text status = Text.empty();
	private long statusUntil;
	private int top;

	public DamageAlertScreen(Screen parent, EffectController controller, String webUrl) {
		super(Text.translatable("damagealert.screen.title"));
		this.parent = parent;
		this.controller = controller;
		this.webUrl = webUrl;
	}

	@Override
	protected void init() {
		buttons.clear();
		toggleOrder.clear();

		int colW = Math.min(200, (this.width - 30) / 2);
		int leftX = this.width / 2 - colW - GAP;
		int rightX = this.width / 2 + GAP;
		int contentH = 12 + 14 + ROW + 14 + 4 * ROW + 36 + BUTTON_H;
		this.top = Math.max(6, (this.height - contentH) / 2);

		int y = top + 12 + 14;
		// Streamer.bot-Events
		addToggle(Effect.DAMAGE, leftX, y, colW - TEST_W - GAP);
		addTestEvent("test.damage", Text.translatable("damagealert.screen.test"),
				EffectController.TestEvent.DAMAGE, leftX + colW - TEST_W, y, TEST_W);
		int portalTestW = 40;
		addToggle(Effect.PORTAL, rightX, y, colW - 2 * portalTestW - 2 * GAP);
		addTestEvent("test.portalStart", Text.translatable("damagealert.screen.start"),
				EffectController.TestEvent.PORTAL_START, rightX + colW - 2 * portalTestW - GAP, y, portalTestW);
		addTestEvent("test.portalEnd", Text.translatable("damagealert.screen.end"),
				EffectController.TestEvent.PORTAL_END, rightX + colW - portalTestW, y, portalTestW);

		// Overlay-Zustaende, zwei Spalten
		y += ROW + 14;
		for (int i = 0; i < STATES.length; i++) {
			Effect effect = STATES[i];
			int x = i % 2 == 0 ? leftX : rightX;
			int rowY = y + (i / 2) * ROW;
			if (effect.isTestableState()) {
				addToggle(effect, x, rowY, colW - TEST_W - GAP);
				ButtonWidget test = ButtonWidget.builder(Text.translatable("damagealert.screen.test"), b -> testState(effect))
						.dimensions(x + colW - TEST_W, rowY, TEST_W, BUTTON_H).build();
				buttons.put("test." + effect.id(), addDrawableChild(test));
			} else {
				addToggle(effect, x, rowY, colW);
			}
		}

		ButtonWidget done = ButtonWidget.builder(ScreenTexts.DONE, b -> close())
				.dimensions(this.width / 2 - 100, top + contentH - BUTTON_H, 200, BUTTON_H).build();
		buttons.put("done", addDrawableChild(done));
	}

	private void addToggle(Effect effect, int x, int y, int w) {
		ButtonWidget b = ButtonWidget.builder(toggleText(effect), btn -> {
			controller.setEnabled(effect, !controller.isEnabled(effect));
			btn.setMessage(toggleText(effect));
		}).dimensions(x, y, w, BUTTON_H).build();
		buttons.put("toggle." + effect.id(), addDrawableChild(b));
		toggleOrder.add(effect);
	}

	private void addTestEvent(String id, Text label, EffectController.TestEvent event, int x, int y, int w) {
		ButtonWidget b = ButtonWidget.builder(label, btn -> {
			showStatus(Text.translatable("damagealert.screen.sending").formatted(Formatting.GRAY), 10_000);
			controller.testEvent(event).thenAccept(result -> {
				// Antwort kommt auf einem Hintergrund-Thread -> auf den Render-Thread wechseln
				if (this.client != null) {
					this.client.execute(() -> showResult(result));
				}
			});
		}).dimensions(x, y, w, BUTTON_H).build();
		buttons.put(id, addDrawableChild(b));
	}

	private void testState(Effect effect) {
		controller.testState(effect);
		showStatus(Text.translatable("damagealert.screen.state_test", effectName(effect),
				EffectController.STATE_TEST_MILLIS / 1000).formatted(Formatting.GREEN), EffectController.STATE_TEST_MILLIS);
	}

	private void showResult(StreamerBotClient.SendResult result) {
		if (result.ok()) {
			showStatus(Text.translatable("damagealert.screen.result_ok", result.status()).formatted(Formatting.GREEN), 6000);
		} else {
			showStatus(Text.translatable("damagealert.screen.result_error", result.error()).formatted(Formatting.RED), 8000);
		}
	}

	private void showStatus(Text text, long millis) {
		this.status = text;
		this.statusUntil = System.currentTimeMillis() + millis;
	}

	static Text effectName(Effect effect) {
		return Text.translatable("damagealert.effect." + effect.id());
	}

	private Text toggleText(Effect effect) {
		boolean on = controller.isEnabled(effect);
		MutableText value = (on ? ScreenTexts.ON : ScreenTexts.OFF).copy().formatted(on ? Formatting.GREEN : Formatting.RED);
		return Text.empty().append(effectName(effect)).append(": ").append(value);
	}

	/** Fuer den automatischen Test. */
	public ButtonWidget button(String id) {
		return buttons.get(id);
	}

	public Text status() {
		return System.currentTimeMillis() < statusUntil ? status : Text.empty();
	}

	@Override
	public void tick() {
		// Aenderungen aus der Weboberflaeche uebernehmen
		for (Effect effect : toggleOrder) {
			ButtonWidget b = buttons.get("toggle." + effect.id());
			Text expected = toggleText(effect);
			if (b != null && !b.getMessage().getString().equals(expected.getString())) {
				b.setMessage(expected);
			}
		}
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		int cx = this.width / 2;
		context.drawCenteredTextWithShadow(this.textRenderer, this.title, cx, top, 0xFFFFFFFF);
		context.drawCenteredTextWithShadow(this.textRenderer,
				Text.translatable("damagealert.screen.events").formatted(Formatting.GRAY), cx, top + 14, 0xFFFFFFFF);
		context.drawCenteredTextWithShadow(this.textRenderer,
				Text.translatable("damagealert.screen.states").formatted(Formatting.GRAY), cx, top + 12 + 14 + ROW + 3, 0xFFFFFFFF);

		int infoY = top + 12 + 14 + ROW + 14 + 4 * ROW + 2;
		context.drawCenteredTextWithShadow(this.textRenderer, status(), cx, infoY, 0xFFFFFFFF);
		Text web = webUrl != null
				? Text.translatable("damagealert.screen.web", webUrl)
				: Text.translatable("damagealert.screen.web_off");
		context.drawCenteredTextWithShadow(this.textRenderer, web.copy().formatted(Formatting.GRAY), cx, infoY + 12, 0xFFFFFFFF);
	}

	@Override
	public void close() {
		if (this.client != null) {
			this.client.setScreen(parent);
		}
	}
}
