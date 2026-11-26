// @ts-check

/**
 * Average a tracker's positive daily totals over the days that have a log.
 * Dates are ISO `YYYY-MM-DD`; untracked days are skipped on both clients.
 * @param {Array<{date: string, amount: number}>} logs
 * @param {string} startDate inclusive ISO day
 * @param {string} endDate inclusive ISO day
 * @param {Set<string>} [untracked]
 * @returns {{average: number, loggedDays: number}}
 */
export function trackerDailyAverage(logs, startDate, endDate, untracked = new Set()) {
  const totals = new Map();
  for (const log of logs) {
    const day = String(log.date).slice(0, 10);
    const amount = Number(log.amount);
    if (day < startDate || day > endDate || untracked.has(day) || !(amount > 0)) continue;
    totals.set(day, (totals.get(day) ?? 0) + amount);
  }
  const values = [...totals.values()];
  if (!values.length) return { average: 0, loggedDays: 0 };
  return {
    average: values.reduce((sum, value) => sum + value, 0) / values.length,
    loggedDays: values.length,
  };
}
