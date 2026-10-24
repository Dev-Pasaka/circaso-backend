package com.circaso.build

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * A build service with one slot: every Test task uses it, so only one test suite runs at a time. Modules still compile in
 * parallel, but two suites that each start ScyllaDB and Valkey containers at once starve the machine (and fail on connect).
 */
abstract class TestLimit : BuildService<BuildServiceParameters.None>
