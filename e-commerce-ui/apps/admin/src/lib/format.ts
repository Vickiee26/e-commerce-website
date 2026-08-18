const usdFormatter = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' })
const dateTimeFormatter = new Intl.DateTimeFormat('en-US', {
  dateStyle: 'medium',
  timeStyle: 'short',
})

/**
 * Catalogue prices carry no currency of their own; USD is a hardcoded constant on the order
 * side (OrderService). There is deliberately no currency parameter here.
 */
export function formatUsd(amount: number): string {
  return usdFormatter.format(amount)
}

export function formatDateTime(iso: string): string {
  return dateTimeFormatter.format(new Date(iso))
}
