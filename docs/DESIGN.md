# A shared visual language

The interface centres on a careful review of a recording: a readable trace, visible source, quiet controls and one clear action at a time.

| Role | Colour | Use |
| --- | --- | --- |
| Midnight | `#18233F` | Instrument surfaces, headings and navigation |
| Iris | `#5646C8` | Primary actions and selection |
| Porcelain | `#F4F5FA` | Workspace background |
| Ice | `#B6C8FF` | Waveforms and controls on dark surfaces |
| Copper | `#F0AA94` | Event and discontinuity accents |
| Ink | `#202B45` | Body text |
| Slate | `#606A80` | Supporting text |

Android uses a Material 3 theme with bundled Manrope weights. The browser embeds the same font. Compact labels stay distinct from measured values, with tabular numerals where available.

Colour supports explicit labels. Preview, Live and Review remain written out. Missing passages break the trace. Transport counters describe delivery; amplitude measurements describe samples.

The browser adapts its navigation, cards and window details for small screens. Keyboard shortcuts supplement ordinary controls, and the motion preference is saved locally. Android controls have minimum 48 dp interaction areas. Device and browser observations are recorded separately in `VALIDATION.md`.
