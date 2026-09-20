package com.closetos.garment.api;

import com.closetos.media.api.MediaAccess;
import com.closetos.wardrobe.api.WardrobeAccess;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class GarmentPresenter {
    private final MediaAccess media;
    private final WardrobeAccess wardrobe;

    GarmentPresenter(MediaAccess media, WardrobeAccess wardrobe) {
        this.media = media;
        this.wardrobe = wardrobe;
    }

    public GarmentDetails detail(GarmentDetails garment) {
        return garment.withAssets(
                media.assetsFor(List.of(garment.id()), wardrobe.currentWardrobeId())
                        .get(garment.id()));
    }

    GarmentPage page(GarmentPage page) {
        var assets =
                media.assetsFor(
                        page.items().stream().map(GarmentDetails::id).toList(),
                        wardrobe.currentWardrobeId());
        return new GarmentPage(
                page.items().stream().map(item -> item.withAssets(assets.get(item.id()))).toList(),
                page.nextCursor());
    }
}
