#!/usr/bin/env bash
# -------------------------------------------------------------
# push_sys_metrics.sh – CPU / RAM / Disk ⇒ Prometheus Pushgateway
# -------------------------------------------------------------
export LC_ALL=C
export LANG=C

PGW_URL="http://localhost:9091/metrics/job/bash_host/instance/$(hostname)"
INTERVAL=10          

# ───────── Linux helpers ──────────────────────────────────────
get_linux() {
  # CPU util %
  read -r _ u n s i _ irq soft steal guest < /proc/stat
  t1=$((u+n+s+i+irq+soft+steal+guest)); i1=$i
  sleep 1
  read -r _ u n s i _ irq soft steal guest < /proc/stat
  t2=$((u+n+s+i+irq+soft+steal+guest)); i2=$i
  CPU=$(awk "BEGIN {printf \"%.2f\", (1-($i2-$i1)/($t2-$t1))*100}")

  # Memory
  mem_total=$(grep MemTotal /proc/meminfo | awk '{print $2*1024}')
  mem_free=$(grep MemAvailable /proc/meminfo | awk '{print $2*1024}')
  mem_used=$((mem_total-mem_free))

  # Disk (/)
  # 3 - использвано памяти 
  # 4 - свободно 
  read -r du df < <(df -P / | awk 'NR==2{print $3" "$4}')
  DISK_USED=$((du*1024))
  DISK_FREE=$((df*1024))
}

# ───────── macOS (Darwin) helpers ─────────────────────────────
get_darwin() {
  # CPU util % (user+sys всех процессов)
  CPU=$(ps -A -o %cpu | tail -n +2 | tr ',' '.' | \
        awk '{sum+=$1} END {printf "%.2f", sum}')

  # Memory
# вывод без названия колонок и тд тока значения 
  mem_total=$(sysctl -n hw.memsize)
  page_size=$(vm_stat | head -1 | awk '{print $8}')
  used_pages=$(vm_stat | awk '
# Отрабатывает тоолько когда видит моотв строку 
# потому что vm_stat парсит построчно 
      /Pages active/      {a=$3}
      /Pages wired/       {w=$3}
      /Pages speculative/ {s=$3}
      END {print a+w+s}' | tr -d '.')
  mem_used=$((used_pages*page_size))
  mem_free=$((mem_total-mem_used))

  # Disk (/)
  read -r du df < <(df -k / | awk 'NR==2{print $3" "$4}')
  DISK_USED=$((du*1024))
  DISK_FREE=$((df*1024))
}

collect() {
  case "$(uname)" in
    Linux)  get_linux  ;;
    Darwin) get_darwin ;;
    *)      echo "Unsupported OS $(uname)"; exit 1 ;;
  esac
}

# ───────── main loop ──────────────────────────────────────────
while true; do
  collect
#прочитать тело запроса из стандартного потока ввода 
  cat <<EOF | curl -s --data-binary @- "$PGW_URL"
# TYPE system_cpu_usage_percent gauge
system_cpu_usage_percent $CPU
# TYPE system_memory_used_bytes gauge
system_memory_used_bytes $mem_used
# TYPE system_memory_total_bytes gauge
system_memory_total_bytes $mem_total
# TYPE system_disk_used_bytes gauge
system_disk_used_bytes $DISK_USED
# TYPE system_disk_free_bytes gauge
system_disk_free_bytes $DISK_FREE
EOF
  sleep "$INTERVAL"
done