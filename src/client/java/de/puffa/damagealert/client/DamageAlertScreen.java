package de.puffa.damagealert.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.puffa.damagealert.DeathCounter;
import de.puffa.damagealert.Effect;
import de.puffa.damagealert.EffectController;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Ingame-Menue (Standard-Taste J): Effekte an/aus schalten, im Overlay testen, Todeszaehler korrigieren.
 * Nutzt denselben {@link EffectController} wie die Weboberflaeche, beide bleiben also synchron.
 * Reine Client-Anzeige: es werden keine Pakete an den Server geschickt.
 */
public class DamageAlertScreen extends Screen {
	private static final int ROW = 22;
	private static final int BUTTON_H = 20;
	private static final int TEST_W = 44;
	private static final int GAP = 4;
	private static final int CONTENT_H = 256;
	private static final Effect[] STATES = {
			Effect.PORTAL, Effect.UNDERWATER,
			Effect.IN_LAVA, Effect.FREEZE,
			Effect.RAINING, Effect.THUNDERING,
			Effect.BIOME, Effect.DIMENSION,
			Effect.Y};

	private final Screen parent;
	private final EffectController controller;
	private final String webUrl;

	/** Alle Buttons nach Id ("toggle.damage", "test.death", "deaths.inc", ...), auch fuer den automatischen Test. */
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
		int cx = this.width / 2;
		this.top = Math.max(4, (this.height - CONTENT_H) / 2);

		// Ereignisse
		int y = top + 24;
		addToggle(Effect.DAMAGE, leftX, y, colW - TEST_W - GAP);
		addEventTest("test.damage", EffectController.TestEvent.DAMAGE, leftX + colW - TEST_W, y);
		addToggle(Effect.DEATH, rightX, y, colW - TEST_W - GAP);
		addEventTest("test.death", EffectController.TestEvent.DEATH, rightX + colW - TEST_W, y);

		// Todeszaehler: -1, +1, Stream auf 0
		y = top + 62;
		addButton("deaths.dec", Text.literal("-1"), cx - 100, y, 40, b -> adjustDeaths(-1));
		addButton("deaths.inc", Text.literal("+1"), cx - 56, y, 40, b -> adjustDeaths(1));
		addButton("deaths.resetSession", Text.translatable("damagealert.screen.deaths_reset_session"), cx - 12, y, 112, b -> {
			controller.deaths().resetSession();
			showStatus(Text.translatable("damagealert.screen.deaths_reset_done").formatted(Formatting.GREEN), 4000);
		});

		// Zustaende, zwei Spalten
		y = top + 98;
		for (int i = 0; i < STATES.length; i++) {
			Effect effect = STATES[i];
			int x = i % 2 == 0 ? leftX : rightX;
			int rowY = y + (i / 2) * ROW;
			if (effect.isTestableState()) {
				addToggle(effect, x, rowY, colW - TEST_W - GAP);
				addButton("test." + effect.id(), Text.translatable("damagealert.screen.test"), x + colW - TEST_W, rowY, TEST_W,
						b -> testState(effect));
			} else {
				addToggle(effect, x, rowY, colW);
			}
		}

		addButton("done", ScreenTexts.DONE, cx - 100, top + CONTENT_H - BUTTON_H, 200, b -> close());
	}

	private void addButton(String id, Text label, int x, int y, int w, ButtonWidget.PressAction action) {
		buttons.put(id, addDrawableChild(ButtonWidget.builder(label, action).dimensions(x, y, w, BUTTON_H).build()));
	}

	private void addToggle(Effect effect, int x, int y, int w) {
		addButton("toggle." + effect.id(), toggleText(effect), x, y, w, btn -> {
			controller.setEnabled(effect, !controller.isEnabled(effect));
			btn.setMessage(toggleText(effect));
		});
		toggleOrder.add(effect);
	}

	private void addEventTest(String id, EffectController.TestEvent event, int x, int y) {
		addButton(id, Text.translatable("damagealert.screen.test"), x, y, TEST_W, b -> {
			controller.testEvent(event);
			showStatus(Text.translatable("damagealert.screen.event_test",
					effectName(event == EffectController.TestEvent.DAMAGE ? Effect.DAMAGE : Effect.DEATH)).formatted(Formatting.GREEN), 4000);
		});
	}

	private void adjustDeaths(int delta) {
		controller.deaths().adjust(delta);
	}

	private void testState(Effect effect) {
		controller.testState(effect);
		showStatus(Text.translatable("damagealert.screen.state_test", effectName(effect),
				EffectController.STATE_TEST_MILLIS / 1000).formatted(Formatting.GREEN), EffectController.STATE_TEST_MILLIS);
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

	public Text deathsText() {
		DeathCounter.Counts c = controller.deaths().get();
		return Text.translatable("damagealert.screen.deaths", c.total(), c.session());
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
				Text.translatable("damagealert.screen.events").formatted(Formatting.GRAY), cx, top + 12, 0xFFFFFFFF);
		context.drawCenteredTextWithShadow(this.textRenderer, deathsText().copy().formatted(Formatting.YELLOW), cx, top + 50, 0xFFFFFFFF);
		context.drawCenteredTextWithShadow(this.textRenderer,
				Text.translatable("damagealert.screen.states").formatted(Formatting.GRAY), cx, top + 86, 0xFFFFFFFF);

		int infoY = top + 98 + 5 * ROW + 2;
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
