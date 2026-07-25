package org.ncssar.rid2caltopo.landrestrictions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LandRestrictionRepositoryTest {
    @Test
    fun buildsArcGisEnvelopeQueryMatchingAppleSources() {
        val source = LandRestrictionRepository.sources.first()
        val url = LandRestrictionRepository.buildQueryUrl(
            source,
            LandCoordinate(39.7392, -104.9903),
            radiusNm = 5.0
        )

        assertEquals("geojson", url.queryParameter("f"))
        assertEquals("esriGeometryEnvelope", url.queryParameter("geometryType"))
        assertEquals("4326", url.queryParameter("outSR"))
        assertEquals("1=1", url.queryParameter("where"))
        assertTrue(url.queryParameter("geometry").orEmpty().split(',').size == 4)
    }

    @Test
    fun federalAndColoradoProvidersExposeAuthoritativeAgencyLinks() {
        assertEquals(4, LandRestrictionRepository.sources.size)
        assertTrue(LandRestrictionRepository.sources.all { it.agency.rulesUrl.startsWith("https://") })
        assertTrue(LandRestrictionRepository.sources.any { it.detailsUrlFields.contains("CPW_URL") })
        assertEquals("RSL_TYPE='NWR'", LandRestrictionRepository.sources.first { it.id == "fws-refuge" }.whereClause)
    }
}
