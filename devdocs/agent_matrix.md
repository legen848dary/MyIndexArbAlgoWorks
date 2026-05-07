# Model Assignment Matrix: Index Arb System Build

| Phase | Component | Assigned Model | Reasoning |
| :--- | :--- | :--- | :--- |
| **0, 1, 2** | **Core Architecture** (Spinal Cord, Market Data, Strategy Engine) | **Claude Sonnet 4.6** | **Architectural Integrity.** In 2026, Sonnet 4.6 is the gold standard for holding complex "Mental Models" of multi-file systems. It is the only model trusted to define the **SBE Schema** and **Aeron IPC** contracts without hallucinating "lazy" object allocations (Zero-GC). |
| **3, 4** | **Business Logic** (Alpha Strategies, Execution, Risk) | **DeepSeek v4 Pro** | **The Logic Factory.** With its massive context window and cost-efficiency, v4 Pro is ideal for the "Fan-out" work. It can take the Interface defined by Claude and rapidly implement 4 distinct strategies and the mock execution gateways without breaking the pattern. |
| **5** | **Frontend** (GUI, WebSocket Bridge) | **DeepSeek v4 Pro** | **Full Stack Speed.** v4 Pro excels at modern React/Vite/Tailwind patterns. It allows for rapid iteration on the UI components (Charts, Grids) without the "verbosity tax" of other models. |
| **6** | **DevOps** (Docker, Orchestration) | **DeepSeek v4 Pro** | **Infrastructure as Code.** It can generate the Dockerfiles and Compose specs quickly. *Constraint:* You must manually verify it respects the `ipc: host` flag for Aeron shared memory. |
| **7** | **Verification** (BDD Tests, Simulation) | **GPT 5.4** | **The Adversarial Auditor.** GPT 5.4 has the highest "Creativity" parameter. It is best suited to "Red Teaming" your system—generating edge cases (e.g., negative prices, broken feeds) and writing natural language Gherkin scenarios that find bugs the other models missed. |
