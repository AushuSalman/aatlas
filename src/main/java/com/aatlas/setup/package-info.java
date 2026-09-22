/**
 * Workspace setup: what data a workspace still has to load before every screen can work.
 *
 * <p>Application module. Reads counts straight from the tables other modules own, through
 * SQL rather than their types, so it can see the whole workspace without depending on each
 * of them. It writes nothing.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "setup",
        allowedDependencies = {"common"})
package com.aatlas.setup;
