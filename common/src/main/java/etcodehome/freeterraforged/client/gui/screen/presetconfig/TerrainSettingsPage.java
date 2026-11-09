package etcodehome.freeterraforged.client.gui.screen.presetconfig;

import java.util.Optional;

import etcodehome.freeterraforged.client.gui.widget.Slider;
import etcodehome.freeterraforged.client.gui.widget.ValueButton;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.network.chat.Component;
import etcodehome.freeterraforged.client.data.FTFTranslationKeys;
import etcodehome.freeterraforged.client.gui.screen.page.LinkedPageScreen.Page;
import etcodehome.freeterraforged.client.gui.screen.presetconfig.PresetListPage.PresetEntry;
import etcodehome.freeterraforged.data.worldgen.preset.settings.ContinentType;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.data.worldgen.preset.settings.TerrainSettings;
import etcodehome.freeterraforged.data.worldgen.preset.settings.RollingHighlandsSettings;
import etcodehome.freeterraforged.data.worldgen.preset.settings.TerrainSettings.General;
import etcodehome.freeterraforged.data.worldgen.preset.settings.TerrainSettings.Terrain;

public class TerrainSettingsPage extends PresetEditorPage {
	private ValueButton<Integer> terrainSeedOffset;
	private Slider terrainRegionSize;
	private Slider globalVerticalScale;
	private Slider globalHorizontalScale;
	private CycleButton<Boolean> fancyMountains;
	private CycleButton<Boolean> legacyMountainScaling;
	private Slider mountainVariety;
	
	private Slider steppeWeight;
	private Slider steppeBaseScale;
	private Slider steppeVerticalScale;
	private Slider steppeHorizontalScale;
	
	private Slider plainsWeight;
	private Slider plainsBaseScale;
	private Slider plainsVerticalScale;
	private Slider plainsHorizontalScale;
	
	private Slider hillsWeight;
	private Slider hillsBaseScale;
	private Slider hillsVerticalScale;
	private Slider hillsHorizontalScale;
	
	private Slider dalesWeight;
	private Slider dalesBaseScale;
	private Slider dalesVerticalScale;
	private Slider dalesHorizontalScale;
	
	private Slider plateauWeight;
	private Slider plateauBaseScale;
	private Slider plateauVerticalScale;
	private Slider plateauHorizontalScale;
	
	private Slider badlandsWeight;
	private Slider badlandsBaseScale;
	private Slider badlandsVerticalScale;
	private Slider badlandsHorizontalScale;
	
	private Slider torridonianWeight;
	private Slider torridonianBaseScale;
	private Slider torridonianVerticalScale;
	private Slider torridonianHorizontalScale;
	
	private Slider mountainsWeight;
	private Slider mountainsBaseScale;
	private Slider mountainsVerticalScale;
	private Slider mountainsHorizontalScale;
	
	private Slider volcanoWeight;
	private Slider volcanoBaseScale;
	private Slider volcanoVerticalScale;
	private Slider volcanoHorizontalScale;

	public TerrainSettingsPage(PresetConfigScreen screen) {
		super(screen);
	}

	@Override
	public Component title() {
		return Component.translatable(FTFTranslationKeys.GUI_TERRAIN_SETTINGS_TITLE);
	}
	
	@Override
	public void init() {
		super.init();

		Preset preset = this.preset.getPreset();
		TerrainSettings terrain = preset.terrain();
        if (preset.world().continent.continentType == ContinentType.ROLLING_HIGHLANDS) {
            terrain.rollingHighlands = terrain.highlands();
            this.left.addWidget(PresetWidgets.createLabel("gui.ftf.highlands.title"));
            this.left.addWidget(PresetWidgets.createLabel("gui.ftf.highlands.scales"));
            addHighlandSlider(terrain, 0, terrain.highlands().uplandSpacing(), 4096, 8192, "gui.ftf.highlands.spacing");
            addHighlandSlider(terrain, 1, terrain.highlands().uplandHeight(), 0, 160, "gui.ftf.highlands.uplands");
            addHighlandSlider(terrain, 2, terrain.highlands().valleyDepth(), 25, 200, "gui.ftf.highlands.depth");
            addHighlandSlider(terrain, 3, terrain.highlands().valleyWidth(), 64, 512, "gui.ftf.highlands.valleys");
            addHighlandSlider(terrain, 4, terrain.highlands().rangeHeight(), 0, RollingHighlandsSettings.MAX_RANGE_HEIGHT, "gui.ftf.highlands.rangeHeight");
            addHighlandSlider(terrain, 5, terrain.highlands().rangeLength(), RollingHighlandsSettings.MIN_RANGE_LENGTH, RollingHighlandsSettings.MAX_RANGE_LENGTH, "gui.ftf.highlands.rangeLength");
            addHighlandSlider(terrain, 6, terrain.highlands().snowline(), RollingHighlandsSettings.MIN_SNOWLINE, RollingHighlandsSettings.MAX_SNOWLINE, "gui.ftf.highlands.snowline");
            addHighlandSlider(terrain, 7, terrain.highlands().rangeRidges(), 1, RollingHighlandsSettings.MAX_RANGE_RIDGES, "gui.ftf.highlands.rangeRidges");
            this.left.addWidget(PresetWidgets.createToggle(terrain.highlands().rangesReplaceMountains(), "gui.ftf.highlands.rangesReplaceMountains", (button, value) -> {
                terrain.rollingHighlands = terrain.highlands().withRangesReplaceMountains(value);
                this.applyRangesReplaceMountains(preset);
                this.regenerate();
            }));
            this.left.addWidget(PresetWidgets.createLabel("gui.ftf.highlands.preview"));
            // RH3 builds on the normal FTF land, so the regular terrain controls follow.
        }
		General general = terrain.general;
		boolean isUpliftContinent = preset.world().continent.continentType == ContinentType.UPLIFT;
		float flooredLowRange = isUpliftContinent ? 1.0F : 0.01F;
		
		this.terrainSeedOffset = PresetWidgets.createRandomButton(FTFTranslationKeys.GUI_BUTTON_TERRAIN_SEED_OFFSET, general.terrainSeedOffset, (value) -> {
			general.terrainSeedOffset = value;
			this.regenerate();
		});
		this.terrainRegionSize = PresetWidgets.createIntSlider(general.terrainRegionSize, 125, 5000, FTFTranslationKeys.GUI_SLIDER_TERRAIN_REGION_SIZE, (slider, value) -> {
			general.terrainRegionSize = (int) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.globalVerticalScale = PresetWidgets.createFloatSlider(general.globalVerticalScale, flooredLowRange, 1.0F, FTFTranslationKeys.GUI_SLIDER_GLOBAL_VERTICAL_SCALE, (slider, value) -> {
			value = isUpliftContinent ? Math.max(slider.getSliderValue(1.0F), value) : value;
			general.globalVerticalScale = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.globalHorizontalScale = PresetWidgets.createFloatSlider(general.globalHorizontalScale, 0.01F, 8.0F, FTFTranslationKeys.GUI_SLIDER_GLOBAL_HORIZONTAL_SCALE, (slider, value) -> {
			general.globalHorizontalScale = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.fancyMountains = PresetWidgets.createToggle(general.fancyMountains, FTFTranslationKeys.GUI_BUTTON_FANCY_MOUNTAINS, (button, value) -> {
			general.fancyMountains = value;
			this.regenerate();
		});
		this.legacyMountainScaling = PresetWidgets.createToggle(general.legacyMountainScaling, FTFTranslationKeys.GUI_BUTTON_LEGACY_MOUNTAIN_SCALING, (button, value) -> {
			general.legacyMountainScaling = value;
			this.regenerate();
		});
		this.mountainVariety = PresetWidgets.createFloatSlider(general.mountainVariety, 0.0F, 1.0F, FTFTranslationKeys.GUI_SLIDER_MOUNTAIN_VARIETY, (slider, value) -> {
			general.mountainVariety = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		Terrain steppe = terrain.steppe;
		this.steppeWeight = PresetWidgets.createFloatSlider(steppe.weight, 0.0F, 10.0F, FTFTranslationKeys.GUI_SLIDER_TERRAIN_WEIGHT, (slider, value) -> {
			steppe.weight = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.steppeBaseScale = PresetWidgets.createFloatSlider(steppe.baseScale, 0.0F, 2.0F, FTFTranslationKeys.GUI_SLIDER_TERRAIN_BASE_SCALE, (slider, value) -> {
			value = isUpliftContinent ? Math.max(slider.getSliderValue(2.0F), value) : value;
			steppe.baseScale = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.steppeVerticalScale = PresetWidgets.createFloatSlider(steppe.verticalScale, 0.0F, 10.0F, FTFTranslationKeys.GUI_SLIDER_TERRAIN_VERTICAL_SCALE, (slider, value) -> {
			steppe.verticalScale = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.steppeHorizontalScale = PresetWidgets.createFloatSlider(steppe.horizontalScale, 0.0F, 10.0F, FTFTranslationKeys.GUI_SLIDER_TERRAIN_HORIZONTAL_SCALE, (slider, value) -> {
			steppe.horizontalScale = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		
		Terrain plains = terrain.plains;
		this.plainsWeight = PresetWidgets.createFloatSlider(plains.weight, 0.0F, 10.0F, FTFTranslationKeys.GUI_SLIDER_TERRAIN_WEIGHT, (slider, value) -> {
			plains.weight = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.plainsBaseScale = PresetWidgets.createFloatSlider(plains.baseScale, 0.0F, 2.0F, FTFTranslationKeys.GUI_SLIDER_TERRAIN_BASE_SCALE, (slider, value) -> {
			value = isUpliftContinent ? Math.max(slider.getSliderValue(2.0F), value) : value;
			plains.baseScale = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.plainsVerticalScale = PresetWidgets.createFloatSlider(plains.verticalScale, 0.0F, 10.0F, FTFTranslationKeys.GUI_SLIDER_TERRAIN_VERTICAL_SCALE, (slider, value) -> {
			plains.verticalScale = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.plainsHorizontalScale = PresetWidgets.createFloatSlider(plains.horizontalScale, 0.0F, 10.0F, FTFTranslationKeys.GUI_SLIDER_TERRAIN_HORIZONTAL_SCALE, (slider, value) -> {
			plains.horizontalScale = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		
		Terrain hills = terrain.hills;
		this.hillsWeight = PresetWidgets.createFloatSlider(hills.weight, 0.0F, 10.0F, FTFTranslationKeys.GUI_SLIDER_TERRAIN_WEIGHT, (slider, value) -> {
			hills.weight = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.hillsBaseScale = PresetWidgets.createFloatSlider(hills.baseScale, 0.0F, 2.0F, FTFTranslationKeys.GUI_SLIDER_TERRAIN_BASE_SCALE, (slider, value) -> {
			value = isUpliftContinent ? Math.max(slider.getSliderValue(1.0F), value) : value;
			hills.baseScale = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.hillsVerticalScale = PresetWidgets.createFloatSlider(hills.verticalScale, 0.0F, 10.0F, FTFTranslationKeys.GUI_SLIDER_TERRAIN_VERTICAL_SCALE, (slider, value) -> {
			hills.verticalScale = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.hillsHorizontalScale = PresetWidgets.createFloatSlider(hills.horizontalScale, 0.0F, 10.0F, FTFTranslationKeys.GUI_SLIDER_TERRAIN_HORIZONTAL_SCALE, (slider, value) -> {
			hills.horizontalScale = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		
		Terrain dales = terrain.dales;
		this.dalesWeight = PresetWidgets.createFloatSlider(dales.weight, 0.0F, 10.0F, FTFTranslationKeys.GUI_SLIDER_TERRAIN_WEIGHT, (slider, value) -> {
			dales.weight = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.dalesBaseScale = PresetWidgets.createFloatSlider(dales.baseScale, flooredLowRange, 2.0F, FTFTranslationKeys.GUI_SLIDER_TERRAIN_BASE_SCALE, (slider, value) -> {
			value = isUpliftContinent ? Math.max(slider.getSliderValue(1.0F), value) : value;
			dales.baseScale = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.dalesVerticalScale = PresetWidgets.createFloatSlider(dales.verticalScale, 0.0F, 10.0F, FTFTranslationKeys.GUI_SLIDER_TERRAIN_VERTICAL_SCALE, (slider, value) -> {
			dales.verticalScale = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.dalesHorizontalScale = PresetWidgets.createFloatSlider(dales.horizontalScale, 0.0F, 10.0F, FTFTranslationKeys.GUI_SLIDER_TERRAIN_HORIZONTAL_SCALE, (slider, value) -> {
			dales.horizontalScale = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		
		Terrain plateau = terrain.plateau;
		this.plateauWeight = PresetWidgets.createFloatSlider(plateau.weight, 0.0F, 10.0F, FTFTranslationKeys.GUI_SLIDER_TERRAIN_WEIGHT, (slider, value) -> {
			plateau.weight = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.plateauBaseScale = PresetWidgets.createFloatSlider(plateau.baseScale, flooredLowRange, 2.0F, FTFTranslationKeys.GUI_SLIDER_TERRAIN_BASE_SCALE, (slider, value) -> {
			value = isUpliftContinent ? Math.max(slider.getSliderValue(1.0F), value) : value;
			plateau.baseScale = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.plateauVerticalScale = PresetWidgets.createFloatSlider(plateau.verticalScale, 0.0F, 10.0F, FTFTranslationKeys.GUI_SLIDER_TERRAIN_VERTICAL_SCALE, (slider, value) -> {
			plateau.verticalScale = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.plateauHorizontalScale = PresetWidgets.createFloatSlider(plateau.horizontalScale, 0.0F, 10.0F, FTFTranslationKeys.GUI_SLIDER_TERRAIN_HORIZONTAL_SCALE, (slider, value) -> {
			plateau.horizontalScale = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		
		Terrain badlands = terrain.badlands;
		this.badlandsWeight = PresetWidgets.createFloatSlider(badlands.weight, 0.0F, 10.0F, FTFTranslationKeys.GUI_SLIDER_TERRAIN_WEIGHT, (slider, value) -> {
			badlands.weight = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.badlandsBaseScale = PresetWidgets.createFloatSlider(badlands.baseScale, flooredLowRange, 2.0F, FTFTranslationKeys.GUI_SLIDER_TERRAIN_BASE_SCALE, (slider, value) -> {
			value = isUpliftContinent ? Math.max(slider.getSliderValue(1.0F), value) : value;
			badlands.baseScale = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.badlandsVerticalScale = PresetWidgets.createFloatSlider(badlands.verticalScale, 0.0F, 10.0F, FTFTranslationKeys.GUI_SLIDER_TERRAIN_VERTICAL_SCALE, (slider, value) -> {
			badlands.verticalScale = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.badlandsHorizontalScale = PresetWidgets.createFloatSlider(badlands.horizontalScale, 0.0F, 10.0F, FTFTranslationKeys.GUI_SLIDER_TERRAIN_HORIZONTAL_SCALE, (slider, value) -> {
			badlands.horizontalScale = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		
		Terrain torridonian = terrain.torridonian;
		this.torridonianWeight = PresetWidgets.createFloatSlider(torridonian.weight, 0.0F, 10.0F, FTFTranslationKeys.GUI_SLIDER_TERRAIN_WEIGHT, (slider, value) -> {
			torridonian.weight = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.torridonianBaseScale = PresetWidgets.createFloatSlider(torridonian.baseScale, 0.0F, 2.0F, FTFTranslationKeys.GUI_SLIDER_TERRAIN_BASE_SCALE, (slider, value) -> {
			torridonian.baseScale = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.torridonianVerticalScale = PresetWidgets.createFloatSlider(torridonian.verticalScale, 0.0F, 10.0F, FTFTranslationKeys.GUI_SLIDER_TERRAIN_VERTICAL_SCALE, (slider, value) -> {
			torridonian.verticalScale = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.torridonianHorizontalScale = PresetWidgets.createFloatSlider(torridonian.horizontalScale, 0.0F, 10.0F, FTFTranslationKeys.GUI_SLIDER_TERRAIN_HORIZONTAL_SCALE, (slider, value) -> {
			torridonian.horizontalScale = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		
		Terrain mountains = terrain.mountains;
		this.mountainsWeight = PresetWidgets.createFloatSlider(mountains.weight, flooredLowRange, 10.0F, FTFTranslationKeys.GUI_SLIDER_TERRAIN_WEIGHT, (slider, value) -> {
			value = isUpliftContinent ? Math.max(slider.getSliderValue(1.0F), value) : value;
			mountains.weight = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.mountainsBaseScale = PresetWidgets.createFloatSlider(mountains.baseScale, 0.0F, 2.0F, FTFTranslationKeys.GUI_SLIDER_TERRAIN_BASE_SCALE, (slider, value) -> {
			mountains.baseScale = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.mountainsVerticalScale = PresetWidgets.createFloatSlider(mountains.verticalScale, 0.0F, 10.0F, FTFTranslationKeys.GUI_SLIDER_TERRAIN_VERTICAL_SCALE, (slider, value) -> {
			mountains.verticalScale = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.mountainsHorizontalScale = PresetWidgets.createFloatSlider(mountains.horizontalScale, 0.0F, 10.0F, FTFTranslationKeys.GUI_SLIDER_TERRAIN_HORIZONTAL_SCALE, (slider, value) -> {
			mountains.horizontalScale = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		
		Terrain volcano = terrain.volcano;
		this.volcanoWeight = PresetWidgets.createFloatSlider(volcano.weight, 0.0F, 10.0F, FTFTranslationKeys.GUI_SLIDER_TERRAIN_WEIGHT, (slider, value) -> {
			volcano.weight = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.volcanoBaseScale = PresetWidgets.createFloatSlider(volcano.baseScale, flooredLowRange, 2.0F, FTFTranslationKeys.GUI_SLIDER_TERRAIN_BASE_SCALE, (slider, value) -> {
			value = isUpliftContinent ? Math.max(slider.getSliderValue(1.0F), value) : value;
			volcano.baseScale = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.volcanoVerticalScale = PresetWidgets.createFloatSlider(volcano.verticalScale, 0.0F, 10.0F, FTFTranslationKeys.GUI_SLIDER_TERRAIN_VERTICAL_SCALE, (slider, value) -> {
			volcano.verticalScale = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});
		this.volcanoHorizontalScale = PresetWidgets.createFloatSlider(volcano.horizontalScale, 0.0F, 10.0F, FTFTranslationKeys.GUI_SLIDER_TERRAIN_HORIZONTAL_SCALE, (slider, value) -> {
			volcano.horizontalScale = (float) slider.scaleValue(value);
			this.regenerate();
			return value;
		});

		PresetWidgets.disableIf(this.globalVerticalScale, isUpliftContinent, "uplift_scale");
		PresetWidgets.disableIf(this.steppeBaseScale, isUpliftContinent, "uplift_scale");
		PresetWidgets.disableIf(this.plainsBaseScale, isUpliftContinent, "uplift_scale");
		PresetWidgets.disableIf(this.hillsBaseScale, isUpliftContinent, "uplift_scale");
		this.applyRangesReplaceMountains(preset);

		this.left.addWidget(PresetWidgets.createLabel(FTFTranslationKeys.GUI_LABEL_GENERAL));
		this.left.addWidget(this.terrainSeedOffset);
		this.left.addWidget(this.terrainRegionSize);
		this.left.addWidget(this.globalVerticalScale);
		this.left.addWidget(this.globalHorizontalScale);
		this.left.addWidget(this.fancyMountains);
		this.left.addWidget(this.legacyMountainScaling);
		this.left.addWidget(this.mountainVariety);

		this.left.addWidget(PresetWidgets.createLabel(FTFTranslationKeys.GUI_LABEL_STEPPE));
		this.left.addWidget(this.steppeWeight);
		this.left.addWidget(this.steppeBaseScale);
		this.left.addWidget(this.steppeVerticalScale);
		this.left.addWidget(this.steppeHorizontalScale);
		
		this.left.addWidget(PresetWidgets.createLabel(FTFTranslationKeys.GUI_LABEL_PLAINS));
		this.left.addWidget(this.plainsWeight);
		this.left.addWidget(this.plainsBaseScale);
		this.left.addWidget(this.plainsVerticalScale);
		this.left.addWidget(this.plainsHorizontalScale);
		
		this.left.addWidget(PresetWidgets.createLabel(FTFTranslationKeys.GUI_LABEL_HILLS));
		this.left.addWidget(this.hillsWeight);
		this.left.addWidget(this.hillsBaseScale);
		this.left.addWidget(this.hillsVerticalScale);
		this.left.addWidget(this.hillsHorizontalScale);
		
		this.left.addWidget(PresetWidgets.createLabel(FTFTranslationKeys.GUI_LABEL_DALES));
		this.left.addWidget(this.dalesWeight);
		this.left.addWidget(this.dalesBaseScale);
		this.left.addWidget(this.dalesVerticalScale);
		this.left.addWidget(this.dalesHorizontalScale);
		
		this.left.addWidget(PresetWidgets.createLabel(FTFTranslationKeys.GUI_LABEL_PLATEAU));
		this.left.addWidget(this.plateauWeight);
		this.left.addWidget(this.plateauBaseScale);
		this.left.addWidget(this.plateauVerticalScale);
		this.left.addWidget(this.plateauHorizontalScale);
		
		this.left.addWidget(PresetWidgets.createLabel(FTFTranslationKeys.GUI_LABEL_BADLANDS));
		this.left.addWidget(this.badlandsWeight);
		this.left.addWidget(this.badlandsBaseScale);
		this.left.addWidget(this.badlandsVerticalScale);
		this.left.addWidget(this.badlandsHorizontalScale);
		
		this.left.addWidget(PresetWidgets.createLabel(FTFTranslationKeys.GUI_LABEL_TORRIDONIAN));
		this.left.addWidget(this.torridonianWeight);
		this.left.addWidget(this.torridonianBaseScale);
		this.left.addWidget(this.torridonianVerticalScale);
		this.left.addWidget(this.torridonianHorizontalScale);
		
		this.left.addWidget(PresetWidgets.createLabel(FTFTranslationKeys.GUI_LABEL_MOUNTAINS));
		this.left.addWidget(this.mountainsWeight);
		this.left.addWidget(this.mountainsBaseScale);
		this.left.addWidget(this.mountainsVerticalScale);
		this.left.addWidget(this.mountainsHorizontalScale);
		
		this.left.addWidget(PresetWidgets.createLabel(FTFTranslationKeys.GUI_LABEL_VOLCANO));
		this.left.addWidget(this.volcanoWeight);
		this.left.addWidget(this.volcanoBaseScale);
		this.left.addWidget(this.volcanoVerticalScale);
		this.left.addWidget(this.volcanoHorizontalScale);
	}

    /**
     * With Rolling Highlands ranges replacing mountains (Range Height above 0 and the toggle on), the mountain terrain
     * weight and the mountain chain strength are both 0 (HighlandLand), so every mountain setting has no effect.
     * Re-run whenever Range Height or the toggle changes.
     */
    private void applyRangesReplaceMountains(Preset preset) {
        if (this.mountainsWeight == null) return; // RH widgets are built before the legacy ones
        boolean replaced = preset.world().continent.continentType == ContinentType.ROLLING_HIGHLANDS
            && preset.terrain().highlands().replacesMountains();
        for (var widget : new net.minecraft.client.gui.components.AbstractWidget[] {
            this.fancyMountains, this.legacyMountainScaling, this.mountainVariety,
            this.mountainsWeight, this.mountainsBaseScale, this.mountainsVerticalScale, this.mountainsHorizontalScale }) {
            PresetWidgets.disableIf(widget, replaced, "highlands.mountains");
        }
    }

    private void addHighlandSlider(TerrainSettings terrain, int field, int initial, int min, int max, String label) {
        this.left.addWidget(PresetWidgets.createIntSlider(initial, min, max, label, (slider, value) -> {
            var old = terrain.highlands();
            int blocks = (int) slider.scaleValue(value);
            terrain.rollingHighlands = new RollingHighlandsSettings(RollingHighlandsSettings.MODEL_VERSION,
                field == 0 ? blocks : old.uplandSpacing(), field == 1 ? blocks : old.uplandHeight(),
                field == 2 ? blocks : old.valleyDepth(), field == 3 ? blocks : old.valleyWidth(), old.meanders(),
                field == 4 ? blocks : old.rangeHeight(), field == 5 ? blocks : old.rangeLength(), old.rangesReplaceMountains(),
                field == 6 ? blocks : old.snowline(), field == 7 ? blocks : old.rangeRidges());
            if (field == 4) this.applyRangesReplaceMountains(this.preset.getPreset());
            this.regenerate();
            return value;
        }));
    }

	@Override
	public Optional<Page> previous() {
		return Optional.of(new ClimateSettingsPage(this.screen));
	}

	@Override
	public Optional<Page> next() {
		return Optional.of(new RiverSettingsPage(this.screen));
	}
}
