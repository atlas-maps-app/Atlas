#!/bin/zsh
# Records a real morning: NDW traffic (open data) and OVapi GTFS-realtime (alerts, trips, vehicles, trains)
# every 5 minutes until END (HHMM). Usage: record_morning.sh <outdir> <END>
OUT=$1; END=${2:-0945}
mkdir -p $OUT/cfg
cd $OUT
# configuration files once
for f in snelheden_en_intensiteiten_configuratie_meetlocaties.xml.gz reistijden_configuratie_meetlocaties.xml.gz; do
  [ -s cfg/$f ] || curl -s -m 120 -o cfg/$f https://opendata.ndw.nu/$f
done
while true; do
  T=$(date +%H%M)
  D=snap_$T; mkdir -p $D
  for f in trafficspeed.xml.gz traveltime.xml.gz snelheden_en_intensiteiten_meetgegevens.xml.gz reistijden_meetgegevens.xml.gz actueel_beeld.xml.gz tijdelijke_verkeersmaatregelen_afsluitingen.xml.gz dynamische_route_informatie_paneel.xml.gz planningsfeed_brugopeningen.xml.gz; do
    curl -s -m 120 -o $D/$f https://opendata.ndw.nu/$f &
  done
  for f in alerts.pb tripUpdates.pb vehiclePositions.pb trainUpdates.pb; do
    curl -s -m 120 -o $D/$f https://gtfs.ovapi.nl/nl/$f &
  done
  wait
  date "+%Y-%m-%dT%H:%M:%S%z" > $D/at.txt
  echo "$T $(du -sh $D | cut -f1)" >> log.txt
  [ "$T" -ge "$END" ] && break
  sleep 300
done
echo done >> log.txt
