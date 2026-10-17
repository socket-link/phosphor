### Orb surfaces

| Page | Browser | Res | Cells | Size px | Style | TTFF ms | render p50 | render p95 | project p95 | frame gap p50 | frame gap p95 | DOM writes/frame |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| compose-wasm | Chrome | 10 | 4801* | 20 | dots | 157 | 0.70 | 0.80 | 0.40 | 8.3 | 9.2 | – |
| compose-wasm | Safari | 10 | 4801* | 20 | dots | 617 | 1.00 | 1.00 | 1.00 | 17.0 | 18.0 | – |
| compose-wasm | Chrome | 10 | 4801* | 200 | dots | 253 | 0.70 | 0.80 | 0.50 | 8.3 | 9.3 | – |
| compose-wasm | Safari | 10 | 4801* | 200 | dots | 569 | 1.00 | 1.00 | 1.00 | 17.0 | 18.0 | – |
| compose-wasm | Chrome | 10 | 4801* | 200 | squares | 430 | 0.80 | 0.90 | 0.40 | 8.3 | 9.3 | – |
| compose-wasm | Safari | 10 | 4801* | 200 | squares | 1291 | 1.00 | 1.00 | 1.00 | 17.0 | 18.0 | – |
| compose-wasm | Chrome | 2 | 81* | 20 | dots | 183 | 0.10 | 0.20 | 0.10 | 8.3 | 9.3 | – |
| compose-wasm | Safari | 2 | 81* | 20 | dots | 592 | 0.00 | 1.00 | 0.00 | 17.0 | 18.0 | – |
| compose-wasm | Chrome | 2 | 81* | 200 | dots | 192 | 0.10 | 0.20 | 0.10 | 8.3 | 9.4 | – |
| compose-wasm | Safari | 2 | 81* | 200 | dots | 557 | 0.00 | 1.00 | 0.00 | 17.0 | 18.0 | – |
| dom-js | Chrome | 10 | 4589 | 20 | Dots | 123 | 4.50 | 5.20 | 4.50 | 16.7 | 17.6 | 12788 |
| dom-js | Safari | 10 | 4612 | 20 | Dots | 465 | 6.00 | 7.00 | 4.00 | 21.0 | 26.0 | 13376 |
| dom-js | Chrome | 10 | 4630 | 200 | Dots | 122 | 6.10 | 7.20 | 4.40 | 16.7 | 25.0 | 14565 |
| dom-js | Safari | 10 | 4570 | 200 | Dots | 448 | 7.00 | 9.00 | 4.00 | 23.0 | 27.0 | 15177 |
| dom-js | Chrome | 10 | 4654 | 200 | Squares | 139 | 7.20 | 8.50 | 4.60 | 16.7 | 25.6 | 18452 |
| dom-js | Safari | 10 | 4576 | 200 | Squares | 445 | 8.00 | 10.00 | 4.00 | 26.0 | 30.0 | 19937 |
| dom-js | Chrome | 2 | 73 | 20 | Dots (currentColor) | 100 | 0.20 | 0.30 | 0.20 | 8.3 | 9.3 | 66 |
| dom-js | Safari | 2 | 69 | 20 | Dots (currentColor) | 394 | 0.00 | 1.00 | 1.00 | 17.0 | 18.0 | 101 |
| dom-js | Chrome | 2 | 73 | 200 | Dots | 102 | 0.40 | 0.60 | 0.20 | 8.3 | 9.3 | 172 |
| dom-js | Safari | 2 | 69 | 200 | Dots | 464 | 0.00 | 1.00 | 1.00 | 17.0 | 18.0 | 220 |
| dom-wasm | Chrome | 10 | 4635 | 20 | Dots | 156 | 12.10 | 13.90 | 0.50 | 17.6 | 25.8 | 13253 |
| dom-wasm | Safari | 10 | 4657 | 20 | Dots | 502 | 15.00 | 18.00 | 1.00 | 29.0 | 36.0 | 13705 |
| dom-wasm | Chrome | 10 | 4604 | 200 | Dots | 133 | 13.80 | 16.20 | 0.50 | 24.9 | 25.8 | 14998 |
| dom-wasm | Safari | 10 | 4580 | 200 | Dots | 506 | 19.00 | 21.00 | 1.00 | 33.0 | 39.0 | 15981 |
| dom-wasm | Chrome | 10 | 4568 | 200 | Squares | 137 | 17.70 | 19.60 | 0.50 | 25.0 | 33.4 | 19758 |
| dom-wasm | Safari | 10 | 4608 | 200 | Squares | 509 | 22.00 | 24.00 | 1.00 | 38.0 | 44.0 | 21146 |
| dom-wasm | Chrome | 2 | 73 | 20 | Dots (currentColor) | 110 | 0.30 | 0.50 | 0.10 | 8.3 | 9.3 | 66 |
| dom-wasm | Safari | 2 | 69 | 20 | Dots (currentColor) | 603 | 0.00 | 1.00 | 0.00 | 17.0 | 18.0 | 100 |
| dom-wasm | Chrome | 2 | 73 | 200 | Dots | 134 | 0.60 | 0.90 | 0.10 | 8.3 | 9.3 | 172 |
| dom-wasm | Safari | 2 | 69 | 200 | Dots | 493 | 1.00 | 2.00 | 0.00 | 17.0 | 19.0 | 220 |

### Trace decode (20 s, 20 fps, resolution 10, v3, 3.83 MB .vxt → 21.2 MB CBOR)

| Page | Browser | decode first ms | decode median ms (5) | gunzip ms | frameAt ms/frame | toFrames ms (400) | buffer copy ms | checksums match JVM |
|---|---|---|---|---|---|---|---|---|
| trace-js | Chrome | 280 | 299 | 285 | 0.20 | 159 | 0 | yes |
| trace-js | Safari | 260 | 237 | 267 | 0.16 | 82 | 0 | yes |
| trace-wasm | Chrome | 261 | 248 | 228 | 0.17 | 107 | 40 | yes |
| trace-wasm | Safari | 258 | 286 | 272 | 0.24 | 79 | 22 | yes |
| JVM reference | – | – | 59 | – | – | 55 | – | – |
