package com.ronin.phoneshm.core.database.repository

import com.ronin.phoneshm.core.database.dao.ProfileDao
import com.ronin.phoneshm.core.database.entity.BuildingProfileEntity
import com.ronin.phoneshm.core.database.entity.MeasurementProfileEntity
import com.ronin.phoneshm.core.database.model.BuildingProfile
import com.ronin.phoneshm.core.database.model.MeasurementProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * ProfileRepositoryImpl maps Domain profiles to/from Room entities and manages database operations.
 */
class ProfileRepositoryImpl(
    private val profileDao: ProfileDao
) : ProfileRepository {

    override suspend fun saveBuildingProfile(profile: BuildingProfile) {
        val entity = BuildingProfileEntity(
            buildingHash = profile.buildingHash,
            displayName = profile.displayName,
            buildingType = profile.buildingType,
            floors = profile.floors,
            constructionYear = profile.constructionYear,
            material = profile.material,
            createdAt = profile.createdAt,
            lastUsedAt = profile.lastUsedAt
        )
        profileDao.insertBuildingProfile(entity)
    }

    override suspend fun getBuildingProfile(buildingHash: String): BuildingProfile? {
        val entity = profileDao.getBuildingProfileByHash(buildingHash) ?: return null
        return BuildingProfile(
            buildingHash = entity.buildingHash,
            displayName = entity.displayName,
            buildingType = entity.buildingType,
            floors = entity.floors,
            constructionYear = entity.constructionYear,
            material = entity.material,
            createdAt = entity.createdAt,
            lastUsedAt = entity.lastUsedAt
        )
    }

    override fun getAllBuildingProfiles(): Flow<List<BuildingProfile>> {
        return profileDao.getAllBuildingProfilesFlow().map { list ->
            list.map { entity ->
                BuildingProfile(
                    buildingHash = entity.buildingHash,
                    displayName = entity.displayName,
                    buildingType = entity.buildingType,
                    floors = entity.floors,
                    constructionYear = entity.constructionYear,
                    material = entity.material,
                    createdAt = entity.createdAt,
                    lastUsedAt = entity.lastUsedAt
                )
            }
        }
    }

    override suspend fun saveMeasurementProfile(profile: MeasurementProfile) {
        val entity = MeasurementProfileEntity(
            id = profile.id,
            buildingId = profile.buildingId,
            floorLevel = profile.floorLevel,
            surfaceType = profile.surfaceType,
            locationType = profile.locationType,
            placement = profile.placement,
            createdAt = profile.createdAt
        )
        profileDao.insertMeasurementProfile(entity)
    }

    override suspend fun getMeasurementProfile(id: String): MeasurementProfile? {
        val entity = profileDao.getMeasurementProfileById(id) ?: return null
        return MeasurementProfile(
            id = entity.id,
            buildingId = entity.buildingId,
            floorLevel = entity.floorLevel,
            surfaceType = entity.surfaceType,
            locationType = entity.locationType,
            placement = entity.placement,
            createdAt = entity.createdAt
        )
    }
}
