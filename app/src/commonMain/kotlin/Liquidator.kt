import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ionspin.kotlin.bignum.decimal.DecimalMode
import com.ionspin.kotlin.bignum.decimal.RoundingMode
import com.ionspin.kotlin.bignum.decimal.toBigDecimal
import com.ionspin.kotlin.bignum.integer.BigInteger
import datalayer.functions.fetchUnderlyingMulticallAssets
import datalayer.functions.getBorrowers
import datalayer.functions.getExchangeRate
import datalayer.functions.simulateLiquidation
import kotlinx.serialization.encodeToString
import msg.overseer.LendOverseerConfig
import msg.overseer.LendOverseerMarket
import msg.overseer.QueryMsg
import types.*
import utils.fetchAllPages

const val PRICES_UPDATE_INTERVAL = 3 * 60 * 1000
val BLACKLISTED_SYMBOLS = listOf("LUNA", "UST")

class Liquidator(
    val repo: Repository,
    private var markets: List<Market>,
    private var constants: LendConstants,
    var storage: Storage,
//    private var manager: LiquidationsManager
) {

    private var isExecuting = false

    companion object {
        suspend fun create(repo: Repository): Liquidator {
            val client = repo.client
            val config = repo.config

            val msg = QueryMsg(
                config = QueryMsg.Config()
            )
            val overseerConfig: LendOverseerConfig = json.decodeFromString(
                client.queryContractSmart(
                    contractAddress = config.overseer.address,
                    contractCodeHash = config.overseer.codeHash,
                    queryMsg = json.encodeToString(msg)
                )
            )

            val allMarkets = fetchAllPages<LendOverseerMarket>({ pagination ->
                json.decodeFromString(
                    client.queryContractSmart(
                        contractAddress = config.overseer.address,
                        contractCodeHash = config.overseer.codeHash,
                        queryMsg = json.encodeToString(QueryMsg(markets = QueryMsg.Markets(pagination))),
                    )
                )
            }, 30u, { x -> !BLACKLISTED_SYMBOLS.contains(x.symbol) })

            val storage = Storage.init(repo, allMarkets.map { it.symbol }.toMutableSet())

            val assets = repo.fetchUnderlyingMulticallAssets(allMarkets)

            val markets = mutableListOf<Market>()

            allMarkets.forEachIndexed { i, market ->
                val m = Market(
                    contract = market.contract,
                    symbol = market.symbol,
                    decimals = market.decimals.toUInt(),
                    underlying = assets[i]
                )
                markets.add(m)
            }
            val constants = LendConstants(
                closeFactor = overseerConfig.close_factor.toBigDecimal(),
                premium = overseerConfig.premium.toBigDecimal()
            )
            return Liquidator(
                repo, markets, constants, storage
            )
        }
    }


    suspend fun runOnce(): List<Loan> {
        return this.runLiquidationsRound()
    }

    fun stop() {
//        if (this.liquidations_handle) {
//            // clearInterval(this.liquidations_handle)
//            // clearInterval(this.prices_update_handle)
//        }
    }

    private suspend fun runLiquidationsRound(): List<Loan> {
        if (isExecuting) {
            return emptyList()
        }

        if (this.storage.userBalance.isZero()) {
            logger.i("Ran out of balance. Terminating...")
            this.stop()

            return emptyList()
        }

        isExecuting = true

        return try {
            storage.updateBlockHeight()

            val candidates = this.markets.map { x -> this.marketCandidate(x) }
            val loans = mutableListOf<Loan>()

            candidates.forEachIndexed { i, candidate ->
                if (candidate != null) {
                    loans.add(
                        Loan(candidate, market = this.markets[i])
                    )
                }
            }
            logger.i(loans.toString())
            loans

//            val liquidation = await this.choose_liquidation(loans)
//
//            if (liquidation) {
//                await this.manager.liquidate(this.storage, liquidation)
//            }
        } catch (t: Throwable) {
            logger.e("Caught an error during liquidations round: ${t.message}")

            t.printStackTrace()
            emptyList()
        } finally {
            isExecuting = false
        }
    }

    private suspend fun marketCandidate(market: Market): Candidate? {
        val candidates = fetchAllPages({ page -> repo.getBorrowers(market, page, storage.blockHeight) }, 1u, { x ->
            if (x.liquidity.shortfall == BigInteger.ZERO) return@fetchAllPages false

            x.markets = x.markets.filter { m -> !BLACKLISTED_SYMBOLS.contains(m.symbol) }

            return@fetchAllPages x.markets.isNotEmpty()
        }).toMutableList()

        if (candidates.isEmpty()) {
            logger.i("No liquidatable loans currently in ${market.contract.address}. Skipping...")

            return null
        }

        return repo.findBestCandidate(market, candidates)
    }

    private suspend fun Repository.findBestCandidate(
        market: Market, borrowers: MutableList<LendMarketBorrower>
    ): Candidate? {
        val sortByPrice: Comparator<LendOverseerMarket> = Comparator { a, b ->
            val priceA = storage.prices[a.symbol]!!
            val priceB = storage.prices[b.symbol]!!
            if (priceA == priceB) {
                return@Comparator 0
            }
            return@Comparator if (priceA > priceB) 1 else -1
        }
        borrowers.forEach { x -> x.markets.sortedWith(sortByPrice) }

        val calcNet = { borrower: LendMarketBorrower ->
            val payable = maxPayable(borrower)

            (payable * constants.premium * storage.prices[borrower.markets[0].symbol]!!).divide(
                storage.prices[market.symbol]!!, DecimalMode(15, RoundingMode.ROUND_HALF_CEILING)
            )
        }

        borrowers.sortWith { a, b ->
            val netA = calcNet(a)
            val netB = calcNet(b)

            if (netA == netB) {
                return@sortWith sortByPrice.compare(a.markets[0], b.markets[0])
            }

            return@sortWith if (netA > netB) 1 else -1
        }

        val exchangeRate = repo.getExchangeRate(market, storage.blockHeight)
        var bestCandidate: Candidate? = null

        // Because we sort the borrowers based on the best case scenario
        // (where full liquidation is possible and receiving the best priced collateral)
        // we can only make assumptions about whether the current loan is the best one to liquidate
        // if we hit the best case scenario for it. So we compare loans in pairs, starting from the `hypothetical`
        // best one and stopping as soon as the best case was encountered for either loan in the current pair.
        // Otherwise, continue to the next pair.
        var i = 0
        do {
            val a = processCandidate(market, borrowers[i], exchangeRate)

            if (a.best_case || i == borrowers.size - 1) {
                bestCandidate = a.candidate

                break
            }

            val b = processCandidate(market, borrowers[i + 1], exchangeRate)

            if (b.candidate.seizable_usd > a.candidate.seizable_usd) {
                bestCandidate = b.candidate

                if (b.best_case) {
                    break
                }
            } else {
                bestCandidate = a.candidate

                break
            }

            i += 2
        } while (i < borrowers.size)

        if (bestCandidate != null && liquidationCostUsd() > bestCandidate.seizable_usd) return null

        return bestCandidate
    }

    data class ProcessCandidateResult(
        val best_case: Boolean, val candidate: Candidate
    )

    private suspend fun processCandidate(
        market: Market, borrower: LendMarketBorrower, exchange_rate: BigDecimal
    ): ProcessCandidateResult {
        val payable = maxPayable(borrower)

        var bestSeizableUsd = BigDecimal.ZERO
        var bestPayable = BigDecimal.ZERO
        var marketIndex = 0

        if (payable < 1) {
            return ProcessCandidateResult(
                best_case = true,
                candidate = Candidate(
                    id = borrower.id,
                    payable = payable,
                    seizable_usd = bestSeizableUsd,
                    market_info = borrower.markets[marketIndex]
                ),
            )
        }

        borrower.markets.forEachIndexed { i, m ->

            // Values are in sl-tokens so we need to convert to
            // the underlying in order for them to be useful here.
            val info = repo.simulateLiquidation(market, borrower, storage.blockHeight, payable)

            val seizable = BigDecimal.fromBigInteger(info.seize_amount).times(exchange_rate)

            if (i == 0 && info.shortfall == BigInteger.ZERO) {
                // We can liquidate using the most profitable asset so no need to go further.
                return ProcessCandidateResult(
                    best_case = true,
                    candidate = Candidate(
                        id = borrower.id,
                        payable = payable,
                        seizable_usd = this.storage.usd_value(seizable, m.symbol, m.decimals),
                        market_info = m
                    ),
                )
            }


            val actual_payable: BigDecimal
            val actual_seizable_usd: BigDecimal

            var done = false

            if (info.shortfall == BigInteger.ZERO) {
                actual_payable = payable
                actual_seizable_usd = this.storage.usd_value(seizable, m.symbol, m.decimals)

                // We don't have to check further since this is the second best scenario that we've got.
                done = true
            } else {
                // Otherwise check by how much we'd need to decrease our repay amount in order for the
                // liquidation to be successful and also decrease the seized amount by that percentage.
                val actual_seizable = info.seize_amount - info.shortfall

                if (actual_seizable.isZero()) {
                    actual_payable = BigDecimal.ZERO
                    actual_seizable_usd = BigDecimal.ZERO
                } else {
                    val seizable_price =
                        BigDecimal.fromBigInteger(actual_seizable) * storage.prices[m.symbol]!! * exchange_rate
                    val borrowed_premium = this.constants.premium * this.storage.prices[market.symbol]!!

                    actual_payable = seizable_price / borrowed_premium

                    actual_seizable_usd =
                        this.storage.usd_value(BigDecimal.fromBigInteger(actual_seizable), m.symbol, m.decimals)
                }
            }

            if (actual_seizable_usd > bestSeizableUsd) {
                bestPayable = actual_payable
                bestSeizableUsd = actual_seizable_usd
                marketIndex = i

                if (done) return@forEachIndexed
            }
        }

        return ProcessCandidateResult(
            best_case = false,
            candidate = Candidate(
                id = borrower.id,
                payable = bestPayable,
                seizable_usd = bestSeizableUsd,
                market_info = borrower.markets[marketIndex]
            ),
        )
    }


    private fun maxPayable(borrower: LendMarketBorrower): BigDecimal {
        return BigDecimal.fromBigInteger(borrower.actualBalance) * constants.closeFactor
    }

    private fun liquidationCostUsd(): BigDecimal {
        return storage.gas_cost_usd(repo.config.gasCosts.liquidate.toBigDecimal())
    }

}

