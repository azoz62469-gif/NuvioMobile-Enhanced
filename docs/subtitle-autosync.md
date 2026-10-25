# Subtitle AutoSync

Subtitle AutoSync compares an add-on subtitle with subtitle timing embedded in the
playing video. Confident results can correct a constant delay, frame-rate drift,
or the timing of individual cue groups. Failed analysis keeps the selected subtitle
and its timing. It does not analyze audio or use speech models.

Enable **Auto Sync Subtitle** in **Settings → Playback → Subtitle / Audio** after
choosing a preferred subtitle language. The v14 switch is on by default after
migration. Its tolerance
and Thorough/Quick search controls appear only while the switch is on. Tolerance
keeps the original timing when the whole-film correction is within the chosen
100–500 ms limit; Off applies all confident corrections.

On Android, AutoSync supports the internal ExoPlayer with HTTP(S) streams and the
subtitle formats handled by its sidecar renderer. On iOS, it supports HTTP(S) and
local `file://` media in the built-in mpv player. Embedded SRT, WebVTT and ASS/SSA timing is read separately
through FFmpeg, and a confident result rewrites only the add-on subtitle timestamps so
mpv keeps its rendering and styling. If the reference read stops early, iOS applies a
validated constant delay only; it does not infer frame-rate drift from a partial track.
Bitmap subtitle references, TTML retiming, and external players
keep their existing subtitle behavior. There is no audio fallback when a video lacks
usable embedded subtitle timing.

When embedded timing is unavailable, iOS may compare the selected subtitle with another
add-on text subtitle only when the selected and reference subtitles belong to the same
requested video identity and season/episode, and the reference's normalized release
identity or release-group/source/cut tuple matches the non-opaque video URL. The selected
subtitle does not need to match the video release because it is the subtitle being
retimed. The text reference must still pass the normal timing confidence gates. A
subtitle match alone is never treated as video evidence. If episode, release/version,
or timing evidence is missing, AutoSync keeps the original subtitle timing and directs
the user to the existing manual delay control.

Success is indicated by **Auto synced** on the selected subtitle card. Only failures
show one manual-fallback toast per media source. The original add-on subtitle is attached while analysis runs, so playback
never waits with the previous subtitle or with subtitles disabled. Changing subtitles,
selecting an embedded track, disabling subtitles, manually adjusting delay, changing the
media source, or leaving playback cancels pending analysis. Index requests start only
after an add-on subtitle is selected for AutoSync. Android swaps sidecar cues without
reloading the media source or discarding its buffer; iOS attaches a retimed temporary
subtitle file to mpv.

AutoSync logs bounded extractor facts such as the reason code, subtitle track counts,
candidate track counts, demux packet count, and EOF state. It does not log the video URL
or subtitle body.

## Sources

- Mobile integration and retimer:
  [NuvioMobile-Reshaped](https://github.com/DavidVamaiotu/NuvioMobile-Reshaped/tree/5332f76a).
- Reviewed matcher, parser, sampled-reference handling, and UX behavior:
  [NuvioTV PR #3703](https://github.com/NuvioMedia/NuvioTV/pull/3703), head `613dbd2d`.
- The extractor rewrapping preserves upstream libass handling underneath AutoSync's
  observation wrapper. Existing parsers, general player preferences, dependencies,
  manifests, branding, and release workflows are kept intact.

## Verification

Local validation covered offsets, drift, grouping, confidence rejection, tolerance,
dense dialogue, reference consistency, no-fit tracking, and subtitle formats. Local
network tests covered subtitle HTTP cancellation, shared index requests, and retrying
an index after its owning request is cancelled. The test files are kept outside
the PR at the author’s request.

Physical-device checks still required:

1. With AutoSync off, verify ordinary subtitle selection and manual delay on Android
   and iOS; verify libmpv and external-player behavior.
2. Enable AutoSync and select an offset or drifting subtitle against an MKV or MP4
   with embedded subtitles. Verify the badge appears without a success toast, and
   playback continues without rebuffering.
3. Check an already-aligned subtitle and a correction inside the selected tolerance.
4. Start analysis, then switch to an embedded subtitle, disable subtitles, choose
   another add-on subtitle, adjust delay, or leave the player. The old result must
   not return or overwrite the new choice.
5. Test a video without embedded subtitles and unsupported ASS/libass rendering.
   Verify one Arabic manual-fallback notice and the original timing.
6. Capture the settings rows and selected subtitle badge for PR review.
