package com.quickskin.mod.neoforge.mixin.compat;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchitecturyCompatMixinPluginTest {
    @Test
    void rewritesThePinnedArchitecturyHandlerShapeAndNothingElse() throws IOException {
        String resource = ArchitecturyCompatMixinPlugin.TARGET_CLASS.replace('.', '/') + ".class";
        ClassNode target = new ClassNode();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new AssertionError("Missing Architectury fixture " + resource);
            new ClassReader(input).accept(target, 0);
        }

        assertEquals(
                new ArchitecturyCompatMixinPlugin.TransformResult(1, 7, 1),
                ArchitecturyCompatMixinPlugin.applyCompatibilityTransform(target)
        );

        ClassWriter writer = new ClassWriter(0);
        target.accept(writer);
        ClassNode roundTrip = new ClassNode();
        new ClassReader(writer.toByteArray()).accept(roundTrip, 0);
        assertThrows(
                IllegalStateException.class,
                () -> ArchitecturyCompatMixinPlugin.applyCompatibilityTransform(roundTrip),
                "a second application must fail rather than silently double-patch"
        );
    }

    @Test
    void appliesOnlyToTheOldNeoForgeEventShape() {
        assertTrue(ArchitecturyCompatMixinPlugin.shouldPatch(true, false, true));
        assertFalse(ArchitecturyCompatMixinPlugin.shouldPatch(true, true, true));
        assertThrows(
                IllegalStateException.class,
                () -> ArchitecturyCompatMixinPlugin.shouldPatch(false, false, true)
        );
        assertThrows(
                IllegalStateException.class,
                () -> ArchitecturyCompatMixinPlugin.shouldPatch(true, false, false)
        );
    }
}
