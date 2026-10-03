package sc.fiji.oc3dsd.ui.tuning;

import java.math.BigDecimal;
import java.util.*;
import sc.fiji.oc3dsd.ui.OC3DSDDialogModel;
import sc.fiji.oc3dsd.internal.flash.ui.variations.ParameterCombo;
import sc.fiji.oc3dsd.internal.flash.ui.variations.ParameterKey;

/** Bounded Cartesian product: every selected value is paired with every other axis. */
public final class TuningPlan {
    public static final int MAX_COMBINATIONS=64;
    public final List<ParameterCombo> combinations;
    public final List<ParameterKey> keys;
    public TuningPlan(OC3DSDDialogModel baseline, Map<TuningParameter,List<Number>> axes) {
        if (axes.isEmpty()) throw new IllegalArgumentException("Select at least one parameter to tune.");
        long count=1;
        for (Map.Entry<TuningParameter,List<Number>> axis:axes.entrySet()) {
            if (!axis.getKey().appliesTo(baseline)) throw new IllegalArgumentException("Z linking is not used by the 3D model.");
            if (axis.getValue().isEmpty()) throw new IllegalArgumentException("Enter values for "+axis.getKey().displayLabel()+".");
            count*=axis.getValue().size();
            if (count>MAX_COMBINATIONS) throw new IllegalArgumentException("This makes more than 64 previews. Reduce the values or selected parameters.");
        }
        keys=Collections.unmodifiableList(new ArrayList<ParameterKey>(axes.keySet()));
        List<Map<ParameterKey,Object>> products=new ArrayList<>(); products.add(new LinkedHashMap<>());
        for (Map.Entry<TuningParameter,List<Number>> axis:axes.entrySet()) {
            List<Map<ParameterKey,Object>> next=new ArrayList<>();
            for(Map<ParameterKey,Object> previous:products) for(Number value:axis.getValue()) {
                Map<ParameterKey,Object> row=new LinkedHashMap<>(previous);
                row.put(axis.getKey(),axis.getKey().checked(value.doubleValue()));next.add(row);
            }
            products=next;
        }
        List<ParameterCombo> result=new ArrayList<>();
        for(Map<ParameterKey,Object> row:products) {
            ParameterCombo combo=new ParameterCombo(row);
            OC3DSDDialogModel m=apply(baseline,combo);
            if (m.minSize>m.maxSize) throw new IllegalArgumentException("A combination has minimum size greater than maximum size.");
            result.add(combo);
        }
        combinations=Collections.unmodifiableList(result);
    }
    public static OC3DSDDialogModel apply(OC3DSDDialogModel baseline, ParameterCombo combo) {
        OC3DSDDialogModel copy=baseline.snapshot();
        for(Map.Entry<ParameterKey,Object> entry:combo.values().entrySet())
            ((TuningParameter)entry.getKey()).apply(copy,(Number)entry.getValue());
        return copy;
    }
    public static List<Number> values(TuningParameter parameter, String text) {
        if(text==null || text.trim().isEmpty()) throw new IllegalArgumentException("Enter values for "+parameter.displayLabel()+".");
        LinkedHashSet<Number> result=new LinkedHashSet<>();
        for(String token:text.trim().split("[,;\\s]+")) {
            double value=(parameter==TuningParameter.MAX_SIZE && "infinity".equalsIgnoreCase(token))?Integer.MAX_VALUE:decimal(token).doubleValue();
            result.add(parameter.checked(value));
            if(result.size()>MAX_COMBINATIONS) throw new IllegalArgumentException("Use at most 64 values per parameter.");
        }
        return new ArrayList<>(result);
    }
    public static List<Number> range(TuningParameter parameter,String start,String end,String step) {
        BigDecimal first=decimal(start), last=decimal(end), increment=decimal(step);
        if(increment.signum()<=0 || first.compareTo(last)>0) throw new IllegalArgumentException("Use a positive step and an end at or above the start.");
        List<Number> result=new ArrayList<>();
        for(BigDecimal v=first;v.compareTo(last)<=0;v=v.add(increment)) {
            if(result.size()==MAX_COMBINATIONS) throw new IllegalArgumentException("The range contains more than 64 values.");
            result.add(parameter.checked(v.doubleValue()));
        }
        return result;
    }
    private static BigDecimal decimal(String text) {
        try {return new BigDecimal(text.trim());}
        catch(RuntimeException e) {throw new IllegalArgumentException("Enter a number (received '"+text+"').");}
    }
}
