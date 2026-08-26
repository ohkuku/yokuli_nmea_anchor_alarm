# Boat Watch phone sensor coordinate frames

> 中文版见后半部分。This document is the product and implementation contract for phone direction, vessel heading, sailing attitude, GNSS and pressure.

## 1. The problem this design fixes

The previous implementation treated “phone fixed to vessel” as one global safety switch. A mount warning could suppress vessel heading and motion together and could block an otherwise valid Phone/App NMEA session. It also asked the user to “set vessel zero” from the phone's current quaternion. That is physically wrong during sailing: if the vessel is already heeled, subtracting the current orientation erases the heel that the trip is meant to record.

Boat Watch now treats each sensor according to its real coordinate frame and use case.

## 2. Physical limits

The phone measures its orientation and motion relative to the Earth. Without another vessel reference it cannot tell whether a rotation came from the vessel or from a person rotating the handset.

For aligned phone vessel heading:

`vessel heading = phone true direction + saved phone-to-bow offset`

Translation of the phone around the vessel does not change that angular relationship. Independent handset rotation does. Ordinary phone GNSS is not accurate enough to recover a phone-to-vessel transform over a few metres, and inertial double integration drifts too quickly. If an independent NMEA HDT/HDG is available, a future assisted-alignment feature may compare it with the phone, but it must not silently rewrite the user's saved offset.

## 3. Independent channels

| Channel | Frame | Setup | What happens when phone is picked up |
|---|---|---|---|
| Android GNSS | Earth position | None | Continues |
| Pressure | Atmosphere | None | Continues |
| Device direction for map/approach | Phone | None | Follows the phone immediately |
| Phone vessel heading | Phone direction + saved bow offset | Confirm offset once | Continues and follows the rotated phone; the user returns it to the aligned direction |
| Heel, pitch and angular rates | Vessel attitude frame | Confirm a valid segment inside Trip Watch | Paused/excluded until the phone is placed and confirmed again |
| Motion score / impact candidates | Derived only from a valid Trip attitude segment | Same as attitude | Paused/excluded |

No attitude state may stop GNSS, pressure, phone navigation direction, heading alignment persistence, the NMEA listener or an established output transport.

## 4. Heading lifecycle

1. Settings → Phone vessel sensors shows the responsive raw phone direction.
2. The user saves the measured angular offset between that direction and the vessel bow.
3. The offset is durable and independent from Trip attitude segments.
4. Reconfirming a Trip attitude frame never clears or version-invalidates heading alignment.
5. Phone vessel heading remains eligible while the handset is not marked fixed for attitude.
6. The UI states the limitation plainly: rotating the handset independently also rotates the reported vessel heading until the handset is returned.

This is an explicit user-controlled instrument source, not an automatic claim that Boat Watch can infer the vessel/phone relative transform after arbitrary movement.

## 5. Trip attitude lifecycle

Attitude is a Trip Watch feature, not a permanent root setting.

### Start

1. In Start Trip Watch, the user optionally enables **Record sailing attitude**.
2. The user places the phone screen plane parallel to the vessel reference plane.
3. The user selects the phone edge pointing toward the bow.
4. The user presses **Phone is placed · confirm**.
5. Boat Watch maps Android device axes to vessel axes. It does **not** subtract the current orientation and does **not** call the current heel `0°`.

If the installation surface aboard the vessel has a permanent slope relative to the vessel design plane, that is a separate static installation correction. It must not be learned from an arbitrary heeled sailing moment.

### While sailing

- The normal workflow is manual: press **Pause attitude** before picking up the phone.
- Pausing affects only attitude, rates, motion score and impact candidates. Every other Trip field continues.
- After replacing the phone, press **Place & confirm**. This begins a new valid segment and records an event with the new frame version.
- Pausing the whole Trip invalidates the active attitude claim because Boat Watch cannot observe what happened to the phone while its sensor lease was stopped. Resume the Trip, then reconfirm attitude if desired.
- Process restoration also requires reconfirmation; it never assumes the phone stayed fixed while the process was absent.

### No automatic pick-up detector

The App does not guess that the phone was picked up from gyroscope or
accelerometer movement: a sailing vessel also yaws, rolls and pitches. The user
owns the live segment boundary with **Pause attitude** and **Place & confirm**.
The report may exclude an isolated, very short statistical spike, but that
post-processing never stops recording and never changes runtime state.

## 6. Trip Report contract

- Only fresh, good-quality samples from valid attitude segments enter heel, pitch, angular-rate, motion-score, roll-period and impact statistics.
- Invalid/paused intervals remain null rather than being filled, held or interpolated.
- An isolated short spike is excluded only when the samples immediately before
  and after agree and fresh GPS COG does not show a vessel turn. Sustained heel,
  gradual changes and tacks are retained.
- The report includes the recorded sailing track and synchronized speed-versus-
  heel summaries, including heel at the fastest synchronized SOG sample.
- Trip Report exposes attitude coverage percentage and motion-unavailable transitions.
- A report with low attitude coverage must not present its attitude statistics as complete-trip facts.
- Heading, position, wind, depth and pressure coverage are calculated independently.

## 7. NMEA publication contract

- Formal Phone/App sharing requires the one-time heading alignment because the product exposes a vessel-heading stream.
- A fixed attitude frame is not a global Start prerequisite.
- `POSITION`, `HEADING` and `PRESSURE` remain independent from Trip attitude state.
- `MOTION` is emitted only while a confirmed Trip attitude segment is active and fresh.
- Picking up the phone never closes a TCP connection, stops the phone-hosted service or clears unrelated streams.

## 8. UI ownership

- **Settings → Phone vessel sensors:** capabilities, raw direction, heading offset, aligned heading preview and a short explanation that Trip attitude lives in Trip Watch.
- **Start Trip Watch:** optional attitude switch, placement instructions, bow-edge selection and confirmation. Start is blocked only when the user selected attitude but has not confirmed its frame.
- **Running Trip:** compact status bar: Recording / Paused. It always exposes Pause or Place & confirm without navigating to Settings.
- **Trip Report:** coverage and invalid-segment-aware statistics.

## 9. Required QA

1. Save heading offset, reconfirm attitude with a new bow edge, and verify heading alignment persists.
2. Start Phone/App NMEA sharing with aligned heading but without an active Trip; Position/Heading/Pressure remain eligible and Motion is absent.
3. Start a Trip while the vessel/phone test fixture is already rolled 20°; confirming the frame must still display approximately 20°, not 0°.
4. Pause attitude, move the phone, and verify all non-attitude Trip data continues while attitude report samples are null.
5. Replace and reconfirm; verify a new segment event and resumed coverage.
6. Move the test fixture like a normal slow vessel roll; runtime recording must continue.
7. Inject one isolated attitude spike between agreeing neighbours; report statistics exclude it. Repeat with a simultaneous GPS COG turn; the tack evidence is retained.
8. Verify the report draws the recorded route and relates synchronized SOG/STW samples to heel bands.

---

# Boat Watch 手机传感器坐标系

## 一、这次重构解决什么

旧实现把“手机是否固定在船上”当成所有手机传感器的总闸门。安装状态异常可能同时停掉船首向、运动数据，甚至阻止一条本来有效的 Phone/App NMEA 会话；旧界面还要求在航行中“设置船体零点”，会把当时已经存在的真实横倾错误地归零。

现在按真实物理含义拆分各通道。

## 二、物理边界

手机只能测量自身相对地球的方向与运动。没有另一个船体参考时，它无法区分“船转了”和“人把手机转了”。

对齐后的手机船首向为：

`船首向 = 手机真方向 + 已保存的手机—船艏偏角`

在船上平移手机不改变这个角度关系；单独旋转手机会改变。普通手机 GPS 无法可靠测量船上几米范围内的手机—船体相对位移，惯性积分也会快速漂移。如果存在独立 NMEA HDT/HDG，未来可以做辅助对齐，但不能静默篡改用户保存的偏角。

## 三、数据通道彼此独立

| 通道 | 坐标系 | 设置 | 拿起手机时 |
|---|---|---|---|
| Android GNSS | 地球位置 | 无 | 继续 |
| 气压 | 大气 | 无 | 继续 |
| 地图/接近导航的手机方向 | 手机 | 无 | 实时跟随手机 |
| 手机船首向 | 手机方向 + 船艏偏角 | 一次性确认 | 继续并跟随手机旋转；放回原方向后恢复正确 |
| 横倾、纵倾、角速度 | 船体姿态 | Trip Watch 内确认有效姿态段 | 暂停并排除，直到重新放好确认 |
| 运动评分、冲击候选 | 只从有效姿态段推算 | 同上 | 暂停并排除 |

姿态状态不得停止 GNSS、气压、手机导航方向、船首向对齐配置、NMEA 监听服务或已经建立的输出连接。

## 四、船首向生命周期

1. “设置 → 手机船舶传感器”显示实时手机方向。
2. 用户保存手机方向相对船艏线的偏角。
3. 偏角长期保存，并与 Trip 姿态段完全独立。
4. 重新确认 Trip 姿态不得清除或让船首向偏角失效。
5. 手机没有标记为固定姿态时，手机船首向仍然可以使用。
6. UI 必须明确：若单独旋转手机，发布的船首向也会随之旋转，直到把手机放回对齐方向。

## 五、航程姿态生命周期

姿态属于 Trip Watch，不属于根设置里的常驻安装流程。

### 开始航程

1. 用户可选“记录航行姿态”。
2. 将手机屏幕平面与船体参考平面平行。
3. 选择手机哪一边指向船艏。
4. 点击“手机已放好 · 确认”。
5. App 只做设备轴到船体轴的映射，不减掉当前姿态；即使船已经横倾，也不会把此刻强行定义为 `0°`。

如果船上放置手机的台面相对船体设计水平面存在固定斜度，应当单独设置静态安装面修正，不能在任意一次倾斜航行中学习。

### 航行中

- 标准操作是：拿起手机前先点“暂停姿态”。
- 暂停只影响姿态、角速度、运动评分和冲击候选；其他航程数据继续记录。
- 放回手机后点“放好并确认”，建立新的有效姿态段。
- 暂停整个航程后，App 无法证明手机在传感器停止期间没有移动；恢复航程后需要重新确认姿态。
- App 进程恢复后同样不擅自相信旧安装状态。

### 不做自动拿起检测

App 不会根据陀螺仪或加速度计猜测手机被拿起，因为帆船本身也会横摇、纵摇和转向。有效姿态段只由用户通过“暂停姿态”和“放好并确认”控制。报告阶段可以排除孤立且很短的统计尖峰，但绝不会因此自动停止运行时记录或改变航程状态。

## 六、Trip Report 规则

- 只有有效姿态段中的新鲜、良好质量样本才进入横倾、纵倾、角速度、运动评分、横摇周期和冲击统计。
- 暂停/无效区间保持为空，不保持旧值、不插值、不猜测。
- 只有当前后相邻样本一致、且新鲜 GPS COG 没有显示船舶转向时，才排除孤立短时尖峰；持续横倾、线性变化和换舷全部保留。
- 报告包含完整航行轨迹，以及同一时间轴上的航速—横倾关系，包括同步样本中最快 SOG 对应的横倾角。
- 报告显示姿态覆盖率和姿态不可用次数。
- 姿态覆盖率较低时，不得把局部统计包装成完整航程结论。
- 船首向、船位、风、水深和气压分别计算覆盖率，不受姿态暂停影响。

## 七、NMEA 发布规则

- Phone/App 正式分享需要一次性船首向对齐，因为产品提供船首向数据流。
- 固定姿态不是全局启动条件。
- `POSITION`、`HEADING`、`PRESSURE` 与 Trip 姿态彼此独立。
- `MOTION` 只在有效且新鲜的 Trip 姿态段内发送。
- 拿起手机绝不会关闭 TCP、停止本机 NMEA 服务或清除无关数据流。

## 八、UI 归属

- **设置 → 手机船舶传感器：**硬件能力、手机原始方向、船艏偏角、对齐后预览，并说明姿态在 Trip Watch 内设置。
- **开始 Trip Watch：**可选姿态记录、放置说明、船艏边选择和确认。只有用户选择了姿态却尚未确认时，开始按钮才被阻止。
- **航程进行中：**紧凑状态条显示“记录中 / 已暂停”，直接提供“暂停”或“放好并确认”。
- **Trip Report：**只统计有效姿态段并显示覆盖率。
