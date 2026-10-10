# Third-party notices

Deadcraft borrows ideas from these projects, and ports code from one of them (ValveResourceFormat, notice below).

| Project | License | What we take |
|---|---|---|
| [SkyCraft](https://github.com/chasmlol/SkyCraft) | MIT | Shared-memory design: header with magic, version, PIDs and heartbeats; per-side seqlocked state structs |
| [Deadworks](https://github.com/Deadworks-net/deadworks) | MIT | Plugin SDK we build against (referenced, not vendored) |
| [ValveResourceFormat](https://github.com/ValveResourceFormat/ValveResourceFormat) | MIT | NuGet library `tools/hero-export` uses to read Deadlock's files and write glTF (referenced, not vendored). Its particle simulation is ported to Java in `fabric-client/.../fx` (the operators' semantics, timing and renderer maths) |
| [MC2CS](https://github.com/dotthegod/MC2CS) | GPL-3.0 | Run as a separate tool in the M1 feel-test pipeline. Not linked or redistributed |

Deadlock and its assets belong to Valve. Minecraft belongs to Mojang and Microsoft. This project is not affiliated with either.

## ValveResourceFormat (ported in fabric-client/src/main/java/dev/deadcraft/client/fx)

The MIT License (MIT)

Copyright (c) 2015 ValveResourceFormat Contributors

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
