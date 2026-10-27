package de.schmodas.multimedia;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;

/** A 0–100 % volume slider ("Volume: 80%"). */
public class VolumeSlider extends AbstractSliderButton {
	private final String label;
	private final IntConsumer onChange;

	public VolumeSlider(int x, int y, int width, String label, int percent, IntConsumer onChange) {
		super(x, y, width, 20, Component.empty(), Math.max(0, Math.min(100, percent)) / 100.0);
		this.label = label;
		this.onChange = onChange;
		updateMessage();
	}

	private int percent() {
		return (int) Math.round(this.value * 100);
	}

	@Override
	protected void updateMessage() {
		setMessage(Component.literal(label + ": " + percent() + "%"));
	}

	@Override
	protected void applyValue() {
		onChange.accept(percent());
	}
}
