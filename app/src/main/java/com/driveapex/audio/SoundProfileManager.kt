package com.driveapex.audio

/** Small registry for selecting and persisting the active sound character later. */
class SoundProfileManager(
    private val engine: EngineSoundEngine,
    private val profiles: List<SoundProfile> = SoundProfiles.profiles
) {
    var activeProfile: SoundProfile = profiles.first()
        private set

    fun select(profileId: String): SoundProfile {
        val selected = profiles.firstOrNull { it.id == profileId } ?: activeProfile
        activeProfile = selected
        engine.setProfile(selected)
        return selected
    }

    fun availableProfiles(): List<SoundProfile> = profiles
}
