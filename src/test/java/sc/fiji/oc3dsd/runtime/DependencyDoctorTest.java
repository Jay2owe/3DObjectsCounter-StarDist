package sc.fiji.oc3dsd.runtime;

import org.junit.Test;
import static org.junit.Assert.*;

/** Regression for Fiji installations carrying protobuf 4 with TensorFlow 1.x models. */
public class DependencyDoctorTest {
    @Test public void incompatibleProtobufIsReportedBeforeNativeInference() {
        // pom-scijava supplies protobuf 4 on the development classpath; Fiji's repair pins 3.5.1.
        String problem = DependencyDoctor.protobufProblem();
        assertNotNull(problem);
        assertTrue(problem.contains("protobuf"));
        assertTrue(problem.contains("Install Runtime"));
        assertTrue(DependencyDoctor.diagnosis().contains(problem));
    }
}
