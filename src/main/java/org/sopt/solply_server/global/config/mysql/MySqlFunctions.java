package org.sopt.solply_server.global.config.mysql;


import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.FunctionContributor;
import org.hibernate.query.sqm.function.SqmFunctionRegistry;
import org.hibernate.type.StandardBasicTypes;

public class MySqlFunctions implements FunctionContributor {
    @Override
    public void contributeFunctions(FunctionContributions fc) {
        SqmFunctionRegistry reg = fc.getFunctionRegistry();

        // 반환 타입: DOUBLE
        // 패턴 기반 등록: match(?1) against (?2 in boolean mode)
        reg.registerPattern(
                "match_against",
                "match(?1) against (?2 in boolean mode)",
                fc.getTypeConfiguration().getBasicTypeRegistry().resolve(StandardBasicTypes.DOUBLE)
        );
    }
}