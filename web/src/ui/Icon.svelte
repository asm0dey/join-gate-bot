<script lang="ts" module>
  // 24×24 stroke paths, drawn in currentColor
  const paths = {
    chevron: 'M9 6l6 6-6 6', back: 'M15 5l-7 7 7 7', up: 'M12 19V5M6 11l6-6 6 6', down: 'M12 5v14M6 13l6 6 6-6',
    trash: 'M4 7h16M10 11v6M14 11v6M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12M9 7V4h6v3',
    plus: 'M12 5v14M5 12h14', close: 'M6 6l12 12M18 6L6 18', refresh: 'M20 11a8 8 0 1 0-2.3 5.7M20 4v7h-7', check: 'M5 12.5l4.5 4.5L19 7.5',
    radio: 'M9 6h11M9 12h11M9 18h11M4.5 6h.01M4.5 12h.01M4.5 18h.01',
    multi: 'M4 6l1.5 1.5L8 5M4 12l1.5 1.5L8 11M4 18l1.5 1.5L8 17M11 6h9M11 12h9M11 18h9',
    yesno: 'M3 12.5l3 3 5-6M14 9l6 6M20 9l-6 6', text: 'M5 6h14M12 6v13M9 19h6', int: 'M9 4L7 20M17 4l-2 16M4 9h16M3 15h16',
    link: 'M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1',
    consent: 'M12 3l7 3v6c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V6z', clock: 'M12 7.5V12l3 2M20.5 12a8.5 8.5 0 1 1-17 0 8.5 8.5 0 0 1 17 0',
  }
  export type IconName = keyof typeof paths
</script>

<script lang="ts">
  let { name, size = 20 }: { name: IconName; size?: number } = $props()
</script>

<svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
  <path d={paths[name]} />
</svg>
