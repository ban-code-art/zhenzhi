package com.example.myapplication.ui.mine.ai

import com.example.myapplication.domain.ai.ModelProfile
import com.example.myapplication.domain.ai.ProfilePurpose
import com.example.myapplication.domain.ai.WireProtocol

/** One virtual connection per profile. Neither endpoint equality nor key sharing merges profiles. */
data class ProfileConnectionProjection(
    val profileId: String,
    val purpose: ProfilePurpose,
    val protocol: WireProtocol,
    val endpoint: String,
    val model: String,
    val hasSecret: Boolean,
) {
    companion object {
        fun from(profile: ModelProfile): ProfileConnectionProjection = ProfileConnectionProjection(
            profileId = profile.id,
            purpose = profile.purpose,
            protocol = profile.protocol,
            endpoint = profile.endpoint,
            model = profile.model,
            hasSecret = !profile.secretRef.isNullOrBlank(),
        )

        fun forPurpose(profiles: List<ModelProfile>, purpose: ProfilePurpose): List<ProfileConnectionProjection> =
            profiles.filter { it.purpose == purpose }.map(::from)
    }
}
