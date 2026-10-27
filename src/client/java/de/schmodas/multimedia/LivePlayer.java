package de.schmodas.multimedia;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * Zeigt den Live-Bildschirm eines Spielers (siehe TvSender/LiveFeed) auf einer Leinwand.
 * Ist der Sender offline, steht "Kein Signal" auf der Leinwand.
 */
public final class LivePlayer implements ScreenSource {
	private static int nextId;

	private final LiveFeed feed;
	private long uploadedSeq;
	private VideoPlayer.VideoTexture texture;
	private final Identifier textureId;

	LivePlayer(String sender, Settings.TvSource target) {
		this(sender, target, false);
	}

	LivePlayer(String sender, Settings.TvSource target, boolean cam) {
		this.feed = LiveFeed.acquire(sender, target, cam);
		this.textureId = Identifier.fromNamespaceAndPath(MultiMediaClient.MOD_ID, (cam ? "cam/" : "live/") + (nextId++));
	}

	LiveFeed feed() {
		return feed;
	}

	@Override
	public Identifier textureId() {
		return textureId;
	}

	@Override
	public boolean hasFrame() {
		return feed.hasFrame();
	}

	@Override
	public String status() {
		return feed.status();
	}

	@Override
	public void upload() {
		if (texture == null) {
			texture = new VideoPlayer.VideoTexture(textureId, feed.width, feed.height);
			Minecraft.getInstance().getTextureManager().register(textureId, texture);
		}
		long seq = feed.copyFrame(uploadedSeq, texture.getPixels().getPointer());
		if (seq != uploadedSeq) {
			uploadedSeq = seq;
			texture.upload();
		}
	}

	@Override
	public void close() {
		feed.release();
		Minecraft.getInstance().execute(() -> {
			if (texture != null) {
				Minecraft.getInstance().getTextureManager().release(textureId);
				texture = null;
			}
		});
	}
}
