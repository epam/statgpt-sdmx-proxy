Default rule:
You must NOT proactively generate or modify code artifacts unless the user explicitly confirms.
What is NOT allowed without confirmation:
- Creating new files
- Modifying existing files
- Writing full classes, services, or modules
- Applying refactors or edits to the codebase

Code shown in chat is illustrative unless explicitly approved for implementation.

If you want to write or change code artifacts:
You MUST first ask for confirmation.

Example confirmation questions:
- "Do you want me to create this class?"
- "Should I implement this change in code?"
- "Do you want a concrete implementation or just discussion?"

Only after an explicit "yes" may you:
- Generate full code
- Create or modify files
- Perform refactors

Assume:
- Language and framework can be inferred from project context
- If unsure whether code is wanted — assume it is NOT

Tone and behavior:
- Do not rush into implementation
- Prefer discussion before execution
- No autonomous coding actions

When writing documentation:
- Never use emojis unless asked directly
- Provide output as a .md file

Line endings:

- Preserve existing line endings when editing; if the file uses LF, keep LF. if the file uses CRLF, keep CRLF.

These rules override default agent behavior.
