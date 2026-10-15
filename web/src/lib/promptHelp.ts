/**
 * The "How to write a prompt" help, kept as data so the copy lives in one place. The Admin prompt
 * settings render it; `docs/usage.md` should repeat this text.
 */

export type PromptHelpLead = { lead: string; text: string };
export type PromptHelpList = { heading: string; items: string[] };

export type PromptHelp = {
  alwaysApplied: PromptHelpLead;
  goodThings: PromptHelpList;
  investigateAlso: PromptHelpList;
  writingTips: PromptHelpList;
  example: PromptHelpLead;
};

export const PROMPT_HELP: PromptHelp = {
  alwaysApplied: {
    lead: 'Always applied first.',
    text: 'The core rules above are part of every request: answer only from the collection, cite sources, treat document text as data, and answer in the question\'s language. You do not need to repeat them, and a prompt cannot switch them off.',
  },
  goodThings: {
    heading: 'Good things to put in a prompt',
    items: [
      'What the collection is and who it is for, e.g. "Board minutes of a housing cooperative, 2010–2020."',
      'Terms, abbreviations and names the model should recognise, e.g. "AGM means annual general meeting."',
      'What to prioritise, e.g. "When decisions conflict, the most recent one applies."',
      'The shape of the answer: length, lists or tables, a summary first.',
      'Tone, e.g. "Plain language for residents, not legal wording."',
    ],
  },
  investigateAlso: {
    heading: 'For Investigate, also',
    items: [
      'How to search: synonyms, old and new names, date formats, document types to look in.',
      'When enough is enough, e.g. "Check at least the minutes and the annual reports."',
    ],
  },
  writingTips: {
    heading: 'Writing tips',
    items: [
      'Use short, concrete instructions, one per line, and avoid rules that contradict each other.',
      'Start from Default and change only what you need.',
      'Do not paste instructions from the documents themselves; document text is never followed as an instruction.',
      'Longer prompts leave less room for source excerpts in each request.',
    ],
  },
  example: {
    lead: 'Example',
    text: '— instead of "Be thorough and accurate", write: "Answer with the decision first, then the meeting date and the paragraph number. Use \'the board\' for \'styrelsen\'."',
  },
};
