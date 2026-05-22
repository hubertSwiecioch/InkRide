# Project Overview: E-Ink Bicycle Computer (For AI Agent)

## 1. Main Project Assumptions
The objective of this project is to develop a **bicycle computer (bike computer) application**. The application is explicitly tailored and optimized for **E-Ink screens**. This technical constraint dictates the front-end architecture, UI rendering strategies, data refresh cycles, and overall visual clarity under direct sunlight.

## 2. Agent Skills & Contextual Behavior
A set of dedicated **skills** is attached to this project. 
* **Operational Rule:** As an AI Agent, you must dynamically analyze the specific scope of work you are currently executing (e.g., UI layout creation, speed calculation logic, GPS data parsing, state management) and strictly invoke the corresponding *skills* relevant to that domain.

## 3. Design System: Mudita Mindful Design (MMD)
The visual and behavioral language of the application must completely align with the philosophy of **Mudita Mindful Design**.

* **CRITICAL MANDATE FOR THE AGENT:** You are **strictly required to prioritize and use the pre-built components from the MMD library**. Do not write custom or bespoke UI elements if an equivalent component or pattern already exists in the provided documentation and repository.

## 4. Key Documentation & Repository Links
You must reference and adhere to the following specifications:
* **E-Ink Guidelines (Design principles for E-Ink displays):** [https://zeroheight.com/956ff055a/p/577bfc-e-ink-guidelines](https://zeroheight.com/956ff055a/p/577bfc-e-ink-guidelines)
* **Component Descriptions:** [https://zeroheight.com/956ff055a/p/36ab9b-components](https://zeroheight.com/956ff055a/p/36ab9b-components)
* **MMD Code Library (GitHub):** [https://github.com/mudita/MMD](https://github.com/mudita/MMD)

## 5. Technical UI Guidelines for E-Ink
* **Minimize Refresh Rates:** Structure views to reduce the need for full-screen refreshes (ghosting prevention/partial refreshes only where supported).
* **High Contrast & Typography:** Adhere strictly to the typographic scales and contrast rules defined in MMD. Avoid subtle grays; rely on sharp, distinct monochromes.
* **No Fluid Animations:** Disable smooth transitions, animations, or hover effects that cause lagging or visual distortion on E-Ink matrices.
