<script lang="ts">
  // Telegram's seven peer colours, picked by id the way its clients do
  const COLORS = ['#e17076', '#faa774', '#a695e7', '#7bc862', '#6ec9cb', '#65aadd', '#ee7aae']
  let { id, name, size = 40 }: { id: number; name: string; size?: number } = $props()
  const initials = $derived(name.split(/\s+/).filter(Boolean).slice(0, 2).map((w) => [...w][0]).join('').toUpperCase() || '?')
</script>

<div class="avatar avatar-placeholder flex-none">
  <div class="rounded-full font-semibold text-white" style="width:{size}px;height:{size}px;font-size:{size * 0.38}px;background:{COLORS[Math.abs(id) % 7]}">
    <span>{initials}</span>
  </div>
</div>
