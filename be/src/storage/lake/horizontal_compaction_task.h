// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#pragma once

#include <memory>
#include <vector>

#include "storage/lake/compaction_task.h"

namespace starrocks {
class Chunk;
class ChunkIterator;
} // namespace starrocks

namespace starrocks::lake {

class Rowset;
class Tablet;
class TabletWriter;

class HorizontalCompactionTask : public CompactionTask {
public:
    explicit HorizontalCompactionTask(int64_t txn_id, int64_t version, std::shared_ptr<Tablet> tablet,
                                      std::vector<std::shared_ptr<Rowset>> input_rowsets)
            : _txn_id(txn_id),
              _version(version),
              _tablet(std::move(tablet)),
              _input_rowsets(std::move(input_rowsets)) {}

    ~HorizontalCompactionTask() override;

    Status execute(Progress* progress) override;

private:
    int64_t _txn_id;
    int64_t _version;
    std::shared_ptr<Tablet> _tablet;
    std::vector<std::shared_ptr<Rowset>> _input_rowsets;
};

} // namespace starrocks::lake
