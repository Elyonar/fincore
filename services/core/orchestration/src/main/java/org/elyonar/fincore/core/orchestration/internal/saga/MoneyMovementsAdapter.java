package org.elyonar.fincore.core.orchestration.internal.saga;

import java.util.UUID;
import org.elyonar.fincore.core.orchestration.api.FundingCommand;
import org.elyonar.fincore.core.orchestration.api.MoneyMovements;
import org.elyonar.fincore.core.orchestration.api.TransferCommand;
import org.elyonar.fincore.core.orchestration.api.TransferResult;
import org.springframework.stereotype.Service;

/**
 * The published surface, delegating to the machinery — nothing more.
 *
 * <p>Exists so a consuming module (Lending, per ADR 0013) binds to
 * {@code orchestration.api.MoneyMovements} and never to the services behind it, which
 * {@code ModuleBoundaryTest} would refuse anyway. Logic added here would be logic hidden from the
 * HTTP path; there must never be any.
 */
@Service
public class MoneyMovementsAdapter implements MoneyMovements {

    private final TransferService transfers;
    private final FundingService funding;
    private final SagaRecords sagas;

    public MoneyMovementsAdapter(TransferService transfers, FundingService funding, SagaRecords sagas) {
        this.transfers = transfers;
        this.funding = funding;
        this.sagas = sagas;
    }

    @Override
    public TransferResult transfer(TransferCommand command) {
        return transfers.transfer(command);
    }

    @Override
    public TransferResult fund(FundingCommand command) {
        return funding.execute(command);
    }

    @Override
    public TransferResult status(UUID tenantId, UUID transactionId) {
        return sagas.read(tenantId, transactionId);
    }
}
