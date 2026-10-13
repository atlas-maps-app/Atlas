#!/bin/zsh
# proofs.sh OLD.html [EXPORT] [BATCH] [EXPECT]: every prompt proof of the studio in one run.
#  1. the export after the batch plan (afterbatch.py), its studio/good equal to qa/pagetest/studio/expected_good.json
#  2. prompt_proof.py: the old page on the export against this page on the export, and against this page on the export after the batch
#     (only the Good screens line may change; after the batch it must read EXPECT); the dumps are kept in proof/
#  3. writeback_proof.sh over the config variants of variants.py (From pool 0, 3, 9, 6 with set entries left off, 4 with holds), and
#     prompt_proof.py on each variant (the old page and this one read it alike, Good screens aside)
# OLD.html: studio_before.html beside this script (the reference page the proofs compare with). Prints one line per proof and "== Studio proofs: N hold, M fail".
here=${0:A:h}; atlas=${here:h:h:h}
OLD=$1; EXP=${2:-$atlas/qa/pagetest/export}; BATCH=${3:-$here/batch.json}
EXPECT=${4:-'A real thing from the place carries the answer. One question, answered at a glance. Visuals instead of text'}
[[ -f $OLD ]] || { echo "usage: proofs.sh OLD.html [EXPORT] [BATCH] [EXPECT]"; exit 2 }
P=${TMPDIR:-/tmp}/atlas_studio_test/proofs; mkdir -p $P; holds=0; fails=0
res() { if [[ $1 == 0 ]]; then holds=$((holds+1)); echo "HOLDS  $2"; else fails=$((fails+1)); echo "FAILS  $2"; fi }
dump() { python3 $here/mkstudio.py $1 $2 $P/h >/dev/null && $here/run.sh $P/h/edit.html 1280 900 light $here/tests/prompts_dump.js > $3 }
echo "== 1. the export after the batch"
python3 $here/afterbatch.py $EXP $BATCH $P/after --expect studio/good=$here/expected_good.json; res $? "afterbatch: $BATCH applied to $EXP"
echo "== 2. prompts: old page against this page"
dump $OLD $EXP $P/old.json; dump $atlas/studio/studio.html $EXP $P/new.json; dump $atlas/studio/studio.html $P/after $P/after.json
cp $P/old.json $here/proof/prompts_before.json; cp $P/new.json $here/proof/prompts_now.json; cp $P/after.json $here/proof/prompts_after_batch.json
python3 $here/prompt_proof.py $P/old.json $P/new.json $EXP/studio/studio/good.json; res $? "prompt_proof: old page vs this page, on the export"
python3 $here/prompt_proof.py $P/old.json $P/after.json $P/after/studio/studio/good.json --expect $EXPECT; res $? "prompt_proof: old page on the export vs this page after the batch"
echo "== 3. write-back over config variants"
for v in $(python3 $here/variants.py $EXP $P/var); do
  echo "-- variant $v"
  cp $EXP/versions.json $P/var/$v/ 2>/dev/null
  $here/writeback_proof.sh $OLD $P/var/$v; res $? "writeback_proof: variant $v"
  dump $OLD $P/var/$v $P/var_old_$v.json; dump $atlas/studio/studio.html $P/var/$v $P/var_new_$v.json
  python3 $here/prompt_proof.py $P/var_old_$v.json $P/var_new_$v.json $P/var/$v/studio/studio/good.json; res $? "prompt_proof: variant $v"
done
echo "== Studio proofs: $holds hold, $fails fail"
[[ $fails == 0 ]]
