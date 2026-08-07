package com.quickskin.mod.e2e;

import com.quickskin.mod.e2e.generated.ScenarioContract;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Binds the executable Java graph to the generated canonical scenario contract. */
final class E2EContractValidator {

    private E2EContractValidator() {}

    static void validate(Scenario scenario, String role, List<Step> actualSteps) {
        if (scenario == null) throw new IllegalArgumentException("scenario is null");
        if (actualSteps == null) throw new IllegalArgumentException("scenario steps are null");
        ScenarioContract.RoleSpec expected = ScenarioContract.role(scenario.id(), role);
        List<ScenarioContract.StepSpec> expectedSteps = expected.steps();
        if (actualSteps.size() != expectedSteps.size()) {
            throw new IllegalStateException("E2E step count drift for "
                    + scenario.id().externalId() + "/" + role + ": expected "
                    + expectedSteps.size() + ", got " + actualSteps.size());
        }

        Set<String> names = new HashSet<>();
        for (int index = 0; index < expectedSteps.size(); index++) {
            ScenarioContract.StepSpec spec = expectedSteps.get(index);
            Step actual = actualSteps.get(index);
            if (actual == null) {
                throw new IllegalStateException("null E2E step at index " + index);
            }
            if (!names.add(actual.name)) {
                throw new IllegalStateException("duplicate executable E2E step " + actual.name);
            }
            if (!spec.id().equals(actual.name)) {
                throw new IllegalStateException("E2E step order drift for "
                        + scenario.id().externalId() + "/" + role + " at index " + index
                        + ": expected " + spec.id() + ", got " + actual.name);
            }
            boolean hasCapture = actual.screenshot != null;
            if (hasCapture != spec.captureRequired()) {
                throw new IllegalStateException("E2E capture drift for "
                        + scenario.id().externalId() + "/" + role + "/" + actual.name
                        + ": expected capture=" + spec.captureRequired()
                        + ", got capture=" + hasCapture);
            }
            if (spec.assertionRequired() && actual.assertion == null) {
                throw new IllegalStateException("required E2E assertion is missing for "
                        + scenario.id().externalId() + "/" + role + "/" + actual.name);
            }
        }
    }
}
