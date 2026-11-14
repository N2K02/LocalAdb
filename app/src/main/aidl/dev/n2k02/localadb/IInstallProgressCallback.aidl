package dev.n2k02.localadb;

/** One-way progress callbacks from the shell process to the initiating app. */
oneway interface IInstallProgressCallback {
    void onStage(String stage);
}
