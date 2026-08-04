package sc.fiji.oc3dsd.equivalence;

import ij.ImagePlus;
import sc.fiji.oc3dsd.MacroOptionsParser;
import sc.fiji.oc3dsd.api.MorphPredicate;
import sc.fiji.oc3dsd.api.OC3DSD;
import sc.fiji.oc3dsd.api.OC3DSDParameters;
import sc.fiji.oc3dsd.api.OC3DSDResult;
import sc.fiji.oc3dsd.engine.OC3DSDRunner;
import sc.fiji.oc3dsd.ui.OC3DSDDialogModel;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/**
 * Runs the corpus × configuration sweep and turns each run into canonical text.
 * <p>
 * The harness drives {@link OC3DSDRunner#measureFilterAndMap} — the same code
 * {@code OC3DSD.run} executes after detection, not a reimplementation of it.
 * That distinction is the whole point: a harness that reproduced the filtering
 * and renumbering logic could not certify that logic, because both copies would
 * drift together.
 * <p>
 * StarDist inference and TrackMate linking are upstream of this seam and are
 * <strong>not exercised</strong>. See {@code docs/migration/DETERMINISM.md} for
 * what that does and does not let the harness certify.
 */
final class Harness {

    static final String GOLDEN_DIR_PROPERTY = "oc3dsd.harness.goldenDir";
    static final String CAPTURE_PROPERTY = "oc3dsd.harness.capture";

    /**
     * The pre-migration reference commit, from SD stage 01 step 0. Goldens are
     * named after this and only this: the seam commit that follows it is
     * behaviour-neutral, and later commits are the migration itself, which is
     * what the goldens exist to judge.
     */
    static final String REFERENCE_SHA = "d4ef7df";

    static {
        // ObjectMapBuilder refuses to build an optional map unless a reserve of
        // free heap remains, and that decision depends on how much heap the JVM
        // happens to have at that moment. A golden that carries the outcome of
        // that check is not reproducible — it would pass or fail on the size of
        // the surefire heap. Setting the documented reserve to zero takes the
        // guard out of the comparison so the harness records the map itself.
        //
        // The guard is therefore NOT covered by the goldens, deliberately. It is
        // a resource policy, not a measurement, and it is covered by its own unit
        // test rather than by an equivalence run.
        System.setProperty(
                sc.fiji.oc3dsd.engine.ObjectMapBuilder.OPTIONAL_MAP_MEMORY_RESERVE_BYTES_PROPERTY,
                "0");
    }

    private Harness() {
    }

    // ------------------------------------------------------------------
    // Locations
    // ------------------------------------------------------------------

    static File goldenRoot() {
        String configured = System.getProperty(GOLDEN_DIR_PROPERTY);
        File base = configured == null || configured.trim().isEmpty()
                ? new File(System.getProperty("user.dir"), "golden")
                : new File(configured.trim());
        return new File(base, REFERENCE_SHA);
    }

    static boolean captureRequested() {
        return Boolean.parseBoolean(System.getProperty(CAPTURE_PROPERTY, "false"));
    }

    static File goldenFile(String fixture, String config) {
        return new File(new File(goldenRoot(), fixture), config + ".txt");
    }

    // ------------------------------------------------------------------
    // The sweep
    // ------------------------------------------------------------------

    static final class Run {
        final Fixtures.Fixture fixture;
        final Sweep.Config config;

        Run(Fixtures.Fixture fixture, Sweep.Config config) {
            this.fixture = fixture;
            this.config = config;
        }
    }

    /**
     * The one map-building configuration many-object fixtures still run, so the
     * map builder's processor-width choice is exercised at their object count
     * rather than merely assumed.
     */
    static final String REPRESENTATIVE_MAP_CONFIG = "baseline";

    /**
     * Every (fixture, configuration) pair the harness runs.
     * <p>
     * <strong>Map building is the expensive part and it scales with object
     * count</strong> — each map carries a numbered overlay, so a fixture with
     * 65,536 objects builds 65,536 text ROIs per map, four times over, for every
     * configuration. Filtering, calibration and the redirect do not scale that
     * way. So the sweep is bounded on that one axis only:
     * <ul>
     *   <li>up to {@link Fixtures.Fixture#FULL_MAP_SWEEP_LIMIT} objects — every
     *       configuration, maps included;</li>
     *   <li>up to {@link Fixtures.Fixture#ANY_MAP_LIMIT} — every non-map
     *       configuration, plus {@link #REPRESENTATIVE_MAP_CONFIG} so the map
     *       builder is still exercised at that object count;</li>
     *   <li>beyond that — non-map configurations only.</li>
     * </ul>
     * Every fixture runs every <em>measurement</em> configuration at every size.
     * The reduction is reported by {@link #reductionNotes()} and written into
     * the golden manifest, so a reduced sweep can never be read as a complete one.
     */
    static List<Run> runs() {
        List<Run> runs = new ArrayList<Run>();
        List<Sweep.Config> configs = Sweep.all();
        // Debugging aid only. Never set when capturing or verifying: a partial
        // sweep would capture a partial golden set, or pass a partial check.
        String only = System.getProperty("oc3dsd.harness.onlyFixture", "");
        for (Fixtures.Fixture fixture : Fixtures.all()) {
            if (!only.isEmpty() && !only.equals(fixture.name)) continue;
            for (int i = 0; i < configs.size(); i++) {
                Sweep.Config config = configs.get(i);
                if (config.buildsAnyMap() && !fixture.allowsMapConfig(config.name)) continue;
                runs.add(new Run(fixture, config));
            }
        }
        return runs;
    }

    /** Human-readable record of every coverage reduction the sweep makes. */
    static String reductionNotes() {
        StringBuilder sb = new StringBuilder();
        List<Sweep.Config> configs = Sweep.all();
        int mapConfigs = 0;
        for (int i = 0; i < configs.size(); i++) {
            if (configs.get(i).buildsAnyMap()) mapConfigs++;
        }
        for (Fixtures.Fixture fixture : Fixtures.all()) {
            if (fixture.allowsAllMapConfigs()) continue;
            boolean any = fixture.allowsMapConfig(REPRESENTATIVE_MAP_CONFIG);
            sb.append("REDUCED ").append(fixture.name)
                    .append(" (~").append(fixture.approximateObjects).append(" objects): runs ")
                    .append(any ? 1 : 0).append(" of ").append(mapConfigs)
                    .append(" map-building configurations. Every measurement configuration")
                    .append(" still runs.\n");
        }
        sb.append("REDUCED tables and partitions above ").append(Canon.FULL_DETAIL_LIMIT)
                .append(" entries record a digest plus the first and last ")
                .append(Canon.DETAIL_WINDOW)
                .append(" rows instead of\n        every row. Comparison there is exact-only,")
                .append(" so a Tier 2 difference inside tolerance reports as a\n")
                .append("        failure to diagnose rather than passing quietly.\n");
        sb.append("EXCLUDED ObjectMapBuilder's optional-map memory guard: its outcome depends")
                .append(" on available heap and\n        therefore cannot be part of a")
                .append(" reproducible golden. The reserve is set to zero for the sweep.\n");
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Recording one run
    // ------------------------------------------------------------------

    static String record(Run run) {
        final List<String> warnings = new ArrayList<String>();
        ImagePlus labels = Fixtures.labels(run.fixture.name);
        ImagePlus intensity = run.config.redirect ? Fixtures.intensityFor(labels) : null;
        run.config.applyCalibration(labels, intensity);

        OC3DSD.Builder builder = OC3DSD.builder(labels)
                .minSize(run.config.minSize)
                .maxSize(run.config.maxSize)
                .excludeOnEdges(run.config.excludeOnEdges)
                .intensityImage(intensity)
                .buildObjectMap(run.config.objectMap)
                .buildSurfaceMap(run.config.surfaceMap)
                .buildCentroidMap(run.config.centroidMap)
                .buildCentreOfMassMap(run.config.comMap)
                .warningSink(new OC3DSDParameters.WarningSink() {
                    @Override
                    public void warn(String message) {
                        warnings.add(message);
                    }
                });
        for (int i = 0; i < run.config.filters.length; i++) {
            builder.addFilter(MorphPredicate.parse(run.config.filters[i]));
        }
        OC3DSDParameters params = builder.build();

        long t0 = System.currentTimeMillis();
        OC3DSDResult result = OC3DSDRunner.measureFilterAndMap(labels, null, params);
        long t1 = System.currentTimeMillis();

        StringBuilder sb = new StringBuilder();
        sb.append("# fixture=").append(run.fixture.name)
                .append(" config=").append(run.config.name).append('\n');
        sb.append("## configuration\n").append(run.config.describe());
        sb.append("## counts\n");
        sb.append("objects=").append(result.getObjectCount()).append('\n');
        sb.append("droppedByMorphology=").append(result.getDroppedByMorphologyFilters()).append('\n');
        sb.append("droppedByDetectorFilters=").append(result.getDroppedByDetectorFilters()).append('\n');
        sb.append("droppedShort=").append(result.getDroppedShortObjects()).append('\n');
        sb.append("singleSlice=").append(result.getSingleSliceObjects()).append('\n');
        // getElapsedMs() is deliberately absent: it is timing, and a golden that
        // carries it can never be reproduced.
        sb.append(warningSection(warnings));
        long t2 = System.currentTimeMillis();
        sb.append(Canon.table("statistics", result.getObjects()));
        long t3 = System.currentTimeMillis();
        sb.append(Canon.table("summary", result.getSummary()));
        long t4 = System.currentTimeMillis();
        sb.append(Canon.partition("labelImage", result.getLabelImage()));
        long t5 = System.currentTimeMillis();
        sb.append(Canon.map("objectMap", result.getObjectMap()));
        sb.append(Canon.map("surfaceMap", result.getSurfaceMap()));
        sb.append(Canon.map("centroidMap", result.getCentroidMap()));
        sb.append(Canon.map("centreOfMassMap", result.getCentreOfMassMap()));
        long t6 = System.currentTimeMillis();
        if (Boolean.getBoolean("oc3dsd.harness.timing")) {
            System.out.println("TIMING " + run.fixture.name + "/" + run.config.name
                    + " engine=" + (t1 - t0) + " counts=" + (t2 - t1)
                    + " stats=" + (t3 - t2) + " summary=" + (t4 - t3)
                    + " partition=" + (t5 - t4) + " maps=" + (t6 - t5));
        }
        return sb.toString();
    }

    /**
     * Warnings are user-visible, so they are compared — but one is emitted per
     * object, so the record is the distinct set plus a count rather than tens of
     * thousands of identical lines.
     */
    private static String warningSection(List<String> warnings) {
        StringBuilder sb = new StringBuilder();
        sb.append("## warnings\n");
        sb.append("count=").append(warnings.size()).append('\n');
        TreeSet<String> distinct = new TreeSet<String>(warnings);
        for (String message : distinct) {
            sb.append("distinct\t").append(message).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Macro option round-trip
    // ------------------------------------------------------------------

    /**
     * Every option the parser understands, parsed, re-recorded and re-parsed.
     * <p>
     * The README points at the wiki for the full option table rather than
     * carrying one, so the corpus is built from {@link MacroOptionsParser}'s own
     * grammar instead — which is a superset of any table, and cannot fall out of
     * date with the parser the way a hand-copied list would.
     */
    static String recordMacroRoundTrip() {
        List<String> cases = new ArrayList<String>();
        cases.add("");
        // Detection options.
        cases.add("channel=2");
        cases.add("model=versatile_fluo");
        cases.add("model=[C:\\models\\my model.zip]");
        cases.add("probability=0.35");
        cases.add("overlap=0.55");
        cases.add("linking_distance=3.5");
        cases.add("gap_distance=7");
        cases.add("slice_gap=2");
        cases.add("min_slices=3");
        // Shared 3D Objects Counter+ vocabulary.
        cases.add("min=25");
        cases.add("max=5000");
        cases.add("max=Infinity");
        cases.add("max=inf");
        cases.add("exclude_edges");
        cases.add("hide_labels");
        cases.add("hide_surfaces");
        cases.add("hide_centroids");
        cases.add("hide_centers_of_mass");
        cases.add("hide_centres_of_mass");
        cases.add("hide_stats");
        cases.add("hide_summary");
        cases.add("save_labels");
        cases.add("redirect=[My Image 01.tif]");
        // Every filter feature the parser advertises.
        cases.add("volume>=100");
        cases.add("volume_calibrated>=1.5");
        cases.add("surface_area<=200");
        cases.add("sphericity>=0.6");
        cases.add("compactness>0.2");
        cases.add("elongation<3");
        cases.add("mean_intensity>=10");
        cases.add("max_intensity>=200");
        cases.add("feret_diameter_max>=5");
        cases.add("fractal_dim_xy>=1.2");
        cases.add("fractal_r2_xy>=0.9");
        cases.add("lacunarity_mean_xy>=0.1");
        cases.add("lacunarity_spread_xy>=0.1");
        cases.add("sholl_critical_radius_um>=1");
        cases.add("sholl_critical_intersections>=2");
        cases.add("sholl_schoenen_index>=0.5");
        cases.add("sholl_primary_branches>=3");
        cases.add("skeleton_branches>=4");
        cases.add("skeleton_junctions>=1");
        cases.add("skeleton_endpoints>=2");
        cases.add("skeleton_voxels>=50");
        cases.add("ri>=0.5");
        cases.add("sri>=0.5");
        cases.add("pb>=0.5");
        cases.add("mp>=0.5");
        cases.add("vsd>=0.5");
        // Combinations, and the README's own example.
        cases.add("channel=1 model=versatile_fluo probability=0.5 overlap=0.4 "
                + "linking_distance=5.0 gap_distance=5.0 slice_gap=1 min_slices=1 "
                + "min=10 sphericity>=0.3 exclude_edges save_labels hide_summary");
        cases.add("min=10 hide_display");
        cases.add("volume>=8 sphericity>=0.2 elongation<=3");
        // Rejections. The error text is user-visible and is part of the contract.
        cases.add("filter1=sphericity>=0.6");
        cases.add("sphericity=0.6");
        cases.add("no_such_feature>=1");
        cases.add("probability=1.5 overlap=-0.2 min_slices=0");
        cases.add("min=abc");
        cases.add("max=");

        StringBuilder sb = new StringBuilder();
        sb.append("# macro option round-trip\n");
        for (int i = 0; i < cases.size(); i++) {
            sb.append(macroCase(cases.get(i)));
        }
        return sb.toString();
    }

    private static String macroCase(String options) {
        StringBuilder sb = new StringBuilder();
        sb.append("## options=").append(options).append('\n');
        try {
            sb.append("hidden=").append(MacroOptionsParser.isHidden(options)).append('\n');
        } catch (RuntimeException e) {
            sb.append("hidden=").append(errorText(e)).append('\n');
        }
        OC3DSDDialogModel parsed;
        try {
            parsed = MacroOptionsParser.parse(options);
        } catch (RuntimeException e) {
            sb.append("parse=").append(errorText(e)).append('\n');
            return sb.toString();
        }
        sb.append("parse=ok\n");
        sb.append(describe("parsed", parsed));

        String recorded;
        try {
            recorded = parsed.toMacroOptions();
        } catch (RuntimeException e) {
            sb.append("record=").append(errorText(e)).append('\n');
            return sb.toString();
        }
        sb.append("recorded=").append(recorded).append('\n');

        try {
            sb.append(describe("replayed", MacroOptionsParser.parse(recorded)));
        } catch (RuntimeException e) {
            sb.append("replay=").append(errorText(e)).append('\n');
        }
        return sb.toString();
    }

    private static String errorText(RuntimeException e) {
        return e.getClass().getName() + ": " + e.getMessage();
    }

    private static String describe(String prefix, OC3DSDDialogModel model) {
        StringBuilder sb = new StringBuilder();
        sb.append(prefix).append(".channel=").append(model.channel).append('\n');
        sb.append(prefix).append(".modelRef=").append(model.modelRef).append('\n');
        sb.append(prefix).append(".probability=").append(Canon.num(model.probability)).append('\n');
        sb.append(prefix).append(".overlap=").append(Canon.num(model.overlap)).append('\n');
        sb.append(prefix).append(".linkingDistance=").append(Canon.num(model.linkingDistance)).append('\n');
        sb.append(prefix).append(".gapDistance=").append(Canon.num(model.gapDistance)).append('\n');
        sb.append(prefix).append(".sliceGap=").append(model.sliceGap).append('\n');
        sb.append(prefix).append(".minSlices=").append(model.minSlices).append('\n');
        sb.append(prefix).append(".minSize=").append(model.minSize).append('\n');
        sb.append(prefix).append(".maxSize=").append(model.maxSize).append('\n');
        sb.append(prefix).append(".excludeOnEdges=").append(model.excludeOnEdges).append('\n');
        sb.append(prefix).append(".showLabels=").append(model.showLabels).append('\n');
        sb.append(prefix).append(".showSurfaces=").append(model.showSurfaces).append('\n');
        sb.append(prefix).append(".showCentroids=").append(model.showCentroids).append('\n');
        sb.append(prefix).append(".showCentersOfMass=").append(model.showCentersOfMass).append('\n');
        sb.append(prefix).append(".showStats=").append(model.showStats).append('\n');
        sb.append(prefix).append(".showSummary=").append(model.showSummary).append('\n');
        sb.append(prefix).append(".saveLabels=").append(model.saveLabels).append('\n');
        sb.append(prefix).append(".redirectTitle=").append(model.redirectTitle).append('\n');
        List<OC3DSDDialogModel.FilterRow> filters = model.filters();
        sb.append(prefix).append(".filters=").append(filters.size()).append('\n');
        for (int i = 0; i < filters.size(); i++) {
            OC3DSDDialogModel.FilterRow row = filters.get(i);
            sb.append(prefix).append(".filter[").append(i).append("]=")
                    .append(row.feature).append(row.operator).append(Canon.num(row.value))
                    .append(" enabled=").append(row.enabled).append('\n');
        }
        List<String> problems = new ArrayList<String>(model.validate());
        Collections.sort(problems);
        sb.append(prefix).append(".validate=").append(problems.size()).append('\n');
        for (int i = 0; i < problems.size(); i++) {
            sb.append(prefix).append(".problem=").append(problems.get(i)).append('\n');
        }
        return sb.toString();
    }
}
