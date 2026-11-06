package com.bkahlert.gametier

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest

internal fun configuredTestVolumes(): List<Path> {
    val configuration = System.getenv("GAMETIER_TEST_VOLUMES")
    assumeTrue(configuration != null, "Run scripts\\Test-Filesystems.ps1 to enable real-filesystem tests")
    val paths = configuration.split(';').map { Path.of(it).toRealPath() }
    require(paths.size == 4) { "GAMETIER_TEST_VOLUMES must contain NTFS1;NTFS2;ReFS1;ReFS2 roots" }
    val stores = paths.map { Files.getFileStore(it) }
    require(stores.map { it.type().uppercase() } == listOf("NTFS", "NTFS", "REFS", "REFS")) {
        "Expected two NTFS and two ReFS volumes, got ${stores.map { it.type() }}"
    }
    require(stores.toSet().size == 4) { "Four distinct volumes are required" }
    return paths
}

internal fun withTestVolumePairs(action: (Path, Path) -> Unit) {
    configuredTestVolumePairs().forEach { (from, to) ->
        withTestVolumeDirectory(from) { source ->
            withTestVolumeDirectory(to) { destination ->
                action(source, destination)
            }
        }
    }
}

internal fun testVolumePairCases(action: (Path, Path) -> Unit): List<DynamicTest> =
    configuredTestVolumePairs().map { (from, to) ->
        dynamicTest("$from -> $to") {
            withTestVolumeDirectory(from) { first ->
                withTestVolumeDirectory(to) { second -> action(first, second) }
            }
        }
    }

internal fun testVolumeCases(action: (Path) -> Unit): List<DynamicTest> =
    configuredTestVolumes().map { volume ->
        dynamicTest(volume.toString()) { withTestVolumeDirectory(volume, action) }
    }

private fun configuredTestVolumePairs(): List<Pair<Path, Path>> {
    val roots = configuredTestVolumes()
    return listOf(0 to 1, 0 to 2, 2 to 3, 2 to 0).map { (from, to) -> roots[from] to roots[to] }
}

internal fun withTestVolumeDirectory(volume: Path, action: (Path) -> Unit) {
    val root = Files.createTempDirectory(volume, "gametier-test-")
    try {
        action(root)
    } finally {
        deleteTestFixture(root)
    }
}
