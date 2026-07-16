import unittest

from qualify_rtmp_lifecycle import qualify_log_lines


class QualifyLogLinesTest(unittest.TestCase):
    def full_chain(self):
        return [
            "MediaMTX INF [RTMP] is publishing to path 'RTMPQUAL1'",
            "FfmpegProbeService Starting FFmpeg render for RTMPQUAL1 url=rtsp://127.0.0.1:8554/RTMPQUAL1",
            "FfmpegProbeService Session lifecycle designator=RTMPQUAL1 sessionId=3 event=decoder_opened phase=primed",
            "StreamTile SurfaceTexture updated for RTMPQUAL1 textureId=42 count=1",
            "StreamTile SurfaceTexture updated for RTMPQUAL1 textureId=42 count=2",
        ]

    def test_passes_with_render_start_and_advancing_same_texture(self):
        evidence = qualify_log_lines(self.full_chain(), "RTMPQUAL1")

        self.assertTrue(evidence.passed)
        self.assertEqual((42, 1, 2), evidence.advancing_texture)

    def test_ignores_evidence_for_another_designator(self):
        evidence = qualify_log_lines(
            [
                "MediaMTX is publishing to path 'OTHER'",
                "Starting FFmpeg render for OTHER url=rtsp://127.0.0.1:8554/OTHER",
                "Session lifecycle designator=OTHER sessionId=1 event=decoder_opened phase=primed",
                "SurfaceTexture updated for OTHER textureId=9 count=1",
                "SurfaceTexture updated for OTHER textureId=9 count=2",
            ],
            "RTMPQUAL1",
        )

        self.assertFalse(evidence.passed)
        self.assertIn("no MediaMTX", evidence.failure_reasons()[0])

    def test_does_not_treat_texture_replacement_as_frame_advancement(self):
        evidence = qualify_log_lines(
            [
                "MediaMTX is publishing to path 'RTMPQUAL1'",
                "Starting FFmpeg render for RTMPQUAL1 url=rtsp://127.0.0.1:8554/RTMPQUAL1",
                "Session lifecycle designator=RTMPQUAL1 sessionId=1 event=decoder_opened phase=primed",
                "SurfaceTexture updated for RTMPQUAL1 textureId=10 count=1",
                "SurfaceTexture updated for RTMPQUAL1 textureId=11 count=2",
            ],
            "RTMPQUAL1",
        )

        self.assertFalse(evidence.passed)
        self.assertIsNone(evidence.advancing_texture)
        self.assertIn("did not advance", evidence.failure_reasons()[0])

    def test_reports_missing_surface_updates_separately(self):
        evidence = qualify_log_lines(
            self.full_chain()[:3],
            "RTMPQUAL1",
        )

        self.assertFalse(evidence.passed)
        self.assertEqual(
            ("no SurfaceTexture updates for RTMPQUAL1; keep its Streams tile visible",),
            evidence.failure_reasons(),
        )

    def test_parses_android_logcat_prefixes(self):
        evidence = qualify_log_lines(
            [
                "07-16 12:00:00.000 100 200 D MediaMTX: is publishing to path 'RTMPQUAL1'",
                "07-16 12:00:00.000 100 200 D FfmpegProbeService: Starting FFmpeg render for RTMPQUAL1 url=x",
                "07-16 12:00:00.050 100 200 D FfmpegProbeService: Session lifecycle designator=RTMPQUAL1 sessionId=4 event=decoder_opened phase=primed",
                "07-16 12:00:00.100 100 100 D StreamTile: SurfaceTexture updated for RTMPQUAL1 textureId=77 count=14",
                "07-16 12:00:00.200 100 100 D StreamTile: SurfaceTexture updated for RTMPQUAL1 textureId=77 count=19",
            ],
            "RTMPQUAL1",
        )

        self.assertTrue(evidence.passed)
        self.assertEqual((77, 14, 19), evidence.advancing_texture)

    def test_fails_when_publishing_stage_is_missing(self):
        evidence = qualify_log_lines(self.full_chain()[1:], "RTMPQUAL1")

        self.assertFalse(evidence.passed)
        self.assertIn("no MediaMTX", evidence.failure_reasons()[0])

    def test_fails_when_render_start_stage_is_missing(self):
        lines = self.full_chain()
        evidence = qualify_log_lines([lines[0], *lines[2:]], "RTMPQUAL1")

        self.assertFalse(evidence.passed)
        self.assertIn("no app-side", evidence.failure_reasons()[0])

    def test_fails_when_decoder_opened_stage_is_missing(self):
        lines = self.full_chain()
        evidence = qualify_log_lines([*lines[:2], *lines[3:]], "RTMPQUAL1")

        self.assertFalse(evidence.passed)
        self.assertIn("event=decoder_opened", evidence.failure_reasons()[0])

    def test_fails_when_stages_are_out_of_order(self):
        lines = self.full_chain()
        evidence = qualify_log_lines([lines[1], lines[0], *lines[2:]], "RTMPQUAL1")

        self.assertFalse(evidence.passed)
        self.assertIn("required publish", evidence.failure_reasons()[0])


if __name__ == "__main__":
    unittest.main()
