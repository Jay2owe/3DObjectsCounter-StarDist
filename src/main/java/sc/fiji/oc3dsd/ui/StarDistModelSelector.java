package sc.fiji.oc3dsd.ui;

import ij.IJ;
import ij.Prefs;
import ij.gui.GenericDialog;
import ij.io.OpenDialog;
import sc.fiji.oc3dsd.MacroOptionsParser;
import sc.fiji.oc3dsd.runtime.ModelResolver;

import java.awt.Choice;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Named 2D models and an import action, shared by single-image and batch dialogs. */
public final class StarDistModelSelector {
    public static final String DEFAULT_LABEL = "Fluorescence nuclei (default)";
    public static final String DSB_LABEL = "Fluorescence nuclei (DSB 2018)";
    public static final String IMPORT_LABEL = "Import model...";
    private static final String PREF = "oc3dsd.importedModel.";
    private static final int MAX_RECENT = 8;
    private final boolean native3d;
    private final Choice choice;
    private final List<String> imported = new ArrayList<String>();
    private Map<String, String> references;
    private String selectedRef;
    private boolean changing;

    public StarDistModelSelector(GenericDialog gd, OC3DSDDialogModel model) {
        native3d = model.is3d();
        if (native3d) {
            gd.addStringField("Model", model.model3d, 30);
            choice = null;
            return;
        }
        for (int i = 0; i < MAX_RECENT; i++) {
            String path = Prefs.get(PREF + i, "");
            if (!path.isEmpty()) imported.add(path);
        }
        selectedRef = normalize(model.modelRef);
        references = choices(selectedRef, imported);
        gd.addChoice("Model", labels(references), labelFor(selectedRef));
        choice = (Choice) gd.getChoices().lastElement();
        choice.addItemListener(event -> {
            if (changing) return;
            if (IMPORT_LABEL.equals(choice.getSelectedItem())) importModel();
            else selectedRef = references.get(choice.getSelectedItem());
        });
    }

    private void importModel() {
        OpenDialog open = new OpenDialog("Import StarDist 2D model (.zip)", null);
        if (open.getFileName() != null) {
            File file = new File(open.getDirectory(), open.getFileName());
            String problem = ModelResolver.validate(file);
            if (problem == null) {
                try {
                    String path = importReference(file);
                    selectedRef = path;
                    imported.remove(path);
                    imported.add(0, path);
                    while (imported.size() > MAX_RECENT) imported.remove(imported.size() - 1);
                    for (int i = 0; i < MAX_RECENT; i++)
                        Prefs.set(PREF + i, i < imported.size() ? imported.get(i) : "");
                    Prefs.savePreferences();
                } catch (IllegalArgumentException invalid) {
                    IJ.error("Import StarDist 2D model", invalid.getMessage());
                }
            } else IJ.error("Import StarDist 2D model", problem);
        }
        changing = true;
        try {
            references = choices(selectedRef, imported);
            choice.removeAll();
            for (String label : labels(references)) choice.add(label);
            choice.select(labelFor(selectedRef));
        } finally { changing = false; }
    }

    public void read(GenericDialog gd, OC3DSDDialogModel model) {
        if (native3d) model.model3d = gd.getNextString();
        else {
            String label = gd.getNextChoice();
            String ref = references.get(label);
            if (ref == null) throw new IllegalArgumentException("Choose a model or finish importing it.");
            model.modelRef = ref;
        }
    }

    private String labelFor(String ref) {
        for (Map.Entry<String, String> entry : references.entrySet())
            if (entry.getValue().equals(ref)) return entry.getKey();
        return DEFAULT_LABEL;
    }

    private static String[] labels(Map<String, String> refs) {
        List<String> result = new ArrayList<String>(refs.keySet());
        result.add(IMPORT_LABEL);
        return result.toArray(new String[result.size()]);
    }

    private static String normalize(String ref) {
        if (ref == null || ref.trim().isEmpty()
                || ModelResolver.BUNDLED_MODEL_KEY.equalsIgnoreCase(ref.trim()))
            return ModelResolver.BUNDLED_MODEL_KEY;
        if (ModelResolver.DSB2018_MODEL_KEY.equalsIgnoreCase(ref.trim()))
            return ModelResolver.DSB2018_MODEL_KEY;
        return ref.trim();
    }

    /** File pickers return Windows separators; recorded ImageJ macros require forward slashes. */
    public static String importReference(File file) {
        String ref = file.getAbsolutePath().replace(File.separatorChar, '/');
        return MacroOptionsParser.requireSafeBracketedValue(ref, "Model path");
    }

    /** Labels are unique even when two imported archives have the same filename. */
    public static LinkedHashMap<String, String> choices(String current, List<String> imports) {
        LinkedHashMap<String, String> result = new LinkedHashMap<String, String>();
        result.put(DEFAULT_LABEL, ModelResolver.BUNDLED_MODEL_KEY);
        result.put(DSB_LABEL, ModelResolver.DSB2018_MODEL_KEY);
        List<String> paths = new ArrayList<String>();
        paths.add(normalize(current));
        paths.addAll(imports);
        for (String path : paths) {
            String ref = normalize(path);
            if (ModelResolver.isBuiltin(ref) || result.containsValue(ref)) continue;
            String name = new File(ref).getName();
            if (name.length() > 30) name = name.substring(0, 27) + "...";
            String label = "Imported: " + name;
            String unique = label;
            int suffix = 2;
            while (result.containsKey(unique)) unique = label + " (" + suffix++ + ")";
            result.put(unique, ref);
        }
        return result;
    }
}
