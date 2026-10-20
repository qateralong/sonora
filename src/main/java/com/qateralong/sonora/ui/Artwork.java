package com.qateralong.sonora.ui;

import com.mojang.blaze3d.platform.NativeImage;
import com.qateralong.sonora.Sonora;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

public final class Artwork {
    public record Entry(ResourceLocation texture, int accent, int dark) {
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final Map<String, Entry> LOADED = new LinkedHashMap<>(64, 0.75F, true);
    private static final Map<String, Boolean> PENDING = new ConcurrentHashMap<>();
    private static int counter;

    private Artwork() {
    }

    public static Entry get(String key, Supplier<byte[]> localBytes) {
        if (key == null) {
            return null;
        }
        Entry entry = LOADED.get(key);
        if (entry != null || PENDING.containsKey(key)) {
            return entry;
        }
        PENDING.put(key, true);
        CompletableFuture.supplyAsync(() -> {
            try {
                if (key.startsWith("local:")) {
                    return localBytes == null ? null : localBytes.get();
                }
                HttpResponse<byte[]> response = HTTP.send(HttpRequest.newBuilder(URI.create(key)).timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                return response.statusCode() == 200 ? response.body() : null;
            } catch (Exception e) {
                return null;
            }
        }).thenAcceptAsync(bytes -> {
            if (bytes != null) {
                try {
                    upload(key, bytes);
                } catch (Exception e) {
                    Sonora.LOGGER.debug("Artwork decode failed for {}", key, e);
                }
            }
        }, Minecraft.getInstance());
        return null;
    }

    private static void upload(String key, byte[] bytes) {
        ByteBuffer buffer = MemoryUtil.memAlloc(bytes.length);
        buffer.put(bytes).flip();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer c = stack.mallocInt(1);
            ByteBuffer pixels = STBImage.stbi_load_from_memory(buffer, w, h, c, 4);
            if (pixels == null) {
                return;
            }
            try {
                int width = w.get(0);
                int height = h.get(0);
                int size = 256;
                NativeImage image = new NativeImage(size, size, false);
                long r = 0;
                long g = 0;
                long b = 0;
                long weight = 0;
                for (int y = 0; y < size; y++) {
                    for (int x = 0; x < size; x++) {
                        int sx = Math.min(width - 1, x * width / size);
                        int sy = Math.min(height - 1, y * height / size);
                        int index = (sy * width + sx) * 4;
                        int pr = pixels.get(index) & 0xFF;
                        int pg = pixels.get(index + 1) & 0xFF;
                        int pb = pixels.get(index + 2) & 0xFF;
                        image.setPixelRGBA(x, y, 0xFF000000 | (pb << 16) | (pg << 8) | pr);
                        int max = Math.max(pr, Math.max(pg, pb));
                        int min = Math.min(pr, Math.min(pg, pb));
                        int saturation = max - min;
                        long wgt = 1 + saturation * saturation / 64 + (max > 60 ? 4 : 0);
                        r += pr * wgt;
                        g += pg * wgt;
                        b += pb * wgt;
                        weight += wgt;
                    }
                }
                int ar = (int) (r / weight);
                int ag = (int) (g / weight);
                int ab = (int) (b / weight);
                int accent = boost(ar, ag, ab);
                int dark = (scale(ar, 0.25F) << 16) | (scale(ag, 0.25F) << 8) | scale(ab, 0.25F);
                ResourceLocation id = Sonora.id("art/" + (counter++));
                DynamicTexture texture = new DynamicTexture(image);
                Minecraft.getInstance().getTextureManager().register(id, texture);
                texture.setFilter(true, false);
                LOADED.put(key, new Entry(id, accent, dark));
                PENDING.remove(key);
                while (LOADED.size() > 48) {
                    String eldest = LOADED.keySet().iterator().next();
                    Entry removed = LOADED.remove(eldest);
                    Minecraft.getInstance().getTextureManager().release(removed.texture());
                }
            } finally {
                STBImage.stbi_image_free(pixels);
            }
        } finally {
            MemoryUtil.memFree(buffer);
        }
    }

    private static int boost(int r, int g, int b) {
        float rf = r / 255.0F;
        float gf = g / 255.0F;
        float bf = b / 255.0F;
        float max = Math.max(rf, Math.max(gf, bf));
        float min = Math.min(rf, Math.min(gf, bf));
        float delta = max - min;
        float hue = 0.0F;
        if (delta > 0.0F) {
            if (max == rf) {
                hue = ((gf - bf) / delta) % 6.0F;
            } else if (max == gf) {
                hue = (bf - rf) / delta + 2.0F;
            } else {
                hue = (rf - gf) / delta + 4.0F;
            }
            hue /= 6.0F;
            if (hue < 0.0F) {
                hue += 1.0F;
            }
        }
        float saturation = max == 0.0F ? 0.0F : delta / max;
        saturation = Math.min(1.0F, saturation * 1.3F + 0.15F);
        float value = Math.max(0.75F, Math.min(1.0F, max * 1.4F));
        return Colors.hsv(hue, saturation, value);
    }

    private static int scale(int value, float factor) {
        return Math.max(0, Math.min(255, (int) (value * factor)));
    }
}
