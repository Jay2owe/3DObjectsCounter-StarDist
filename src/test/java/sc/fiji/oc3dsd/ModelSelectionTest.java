package sc.fiji.oc3dsd;

import org.junit.Test;
import sc.fiji.oc3dsd.runtime.ModelResolver;
import sc.fiji.oc3dsd.ui.OC3DSDDialogModel;
import sc.fiji.oc3dsd.ui.StarDistModelSelector;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.zip.ZipFile;
import static org.junit.Assert.*;

public class ModelSelectionTest {
    @Test public void defaultAndBenchmarkModelsResolveToDistinctSavedModels() throws Exception {
        File current = ModelResolver.resolve(ModelResolver.BUNDLED_MODEL_KEY);
        File benchmark = ModelResolver.resolve("DSB2018");
        try (ZipFile first = new ZipFile(current); ZipFile second = new ZipFile(benchmark)) {
            assertNotNull(first.getEntry("saved_model.pb"));
            assertNotNull(second.getEntry("saved_model.pb"));
        }
        assertFalse(Arrays.equals(Files.readAllBytes(current.toPath()), Files.readAllBytes(benchmark.toPath())));
        assertEquals(current, ModelResolver.resolve(""));
    }

    @Test public void benchmarkSelectionKeepsTheMacroContractAndValidatesAsBuiltin() {
        OC3DSDDialogModel model = MacroOptionsParser.parse("model=dsb2018");
        assertTrue(model.validate().isEmpty());
        assertEquals("dsb2018", MacroOptionsParser.parse(model.toMacroOptions()).modelRef);
    }

    @Test public void importedModelsWithSameFilenameRemainDistinctAndDoNotDuplicatePaths() {
        String one = new File("study one", "nuclei.zip").getAbsolutePath();
        String two = new File("study two", "nuclei.zip").getAbsolutePath();
        LinkedHashMap<String,String> choices = StarDistModelSelector.choices(one, Arrays.asList(one, two));
        assertEquals(4, choices.size());
        assertEquals(one, choices.get("Imported: nuclei.zip"));
        assertEquals(two, choices.get("Imported: nuclei.zip (2)"));
        assertEquals(one, MacroOptionsParser.parse("model=[" + one + "]").modelRef);
    }

    @Test public void defaultIsFirstAndTheImportActionIsNeverAStoredModelReference() {
        LinkedHashMap<String,String> choices = StarDistModelSelector.choices(null, Collections.<String>emptyList());
        assertEquals(StarDistModelSelector.DEFAULT_LABEL, choices.keySet().iterator().next());
        assertEquals(ModelResolver.BUNDLED_MODEL_KEY, choices.get(StarDistModelSelector.DEFAULT_LABEL));
        assertFalse(choices.containsKey(StarDistModelSelector.IMPORT_LABEL));
    }
}
