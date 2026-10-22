package com.nuvio.app.features.profiles

/** Only identity is needed by the production preference repository in JVM tests. */
object ProfileRepository {
    var activeProfileId: Int = 0
}
